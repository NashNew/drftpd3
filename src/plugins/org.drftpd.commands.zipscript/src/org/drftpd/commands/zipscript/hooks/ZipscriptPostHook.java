/*
 * This file is part of DrFTPD, Distributed FTP Daemon.
 *
 * DrFTPD is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * DrFTPD is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with DrFTPD; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package org.drftpd.commands.zipscript.hooks;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;
import java.util.ResourceBundle;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.drftpd.Bytes;
import org.drftpd.Checksum;
import org.drftpd.GlobalContext;
import org.drftpd.RankUtils;
import org.drftpd.SFVInfo;
import org.drftpd.SFVStatus;
import org.drftpd.commandmanager.CommandRequest;
import org.drftpd.commandmanager.CommandResponse;
import org.drftpd.commandmanager.PostHookInterface;
import org.drftpd.commandmanager.StandardCommandManager;
import org.drftpd.commands.dataconnection.DataConnectionHandler;
import org.drftpd.commands.dir.Dir;
import org.drftpd.commands.zipscript.LinkUtils;
import org.drftpd.commands.zipscript.vfs.ZipscriptVFSDataSFV;
import org.drftpd.dynamicdata.KeyNotFoundException;
import org.drftpd.exceptions.NoAvailableSlaveException;
import org.drftpd.usermanager.NoSuchUserException;
import org.drftpd.usermanager.User;
import org.drftpd.usermanager.UserFileException;
import org.drftpd.util.GroupPosition;
import org.drftpd.util.UploaderPosition;
import org.drftpd.vfs.DirectoryHandle;
import org.drftpd.vfs.FileHandle;
import org.drftpd.vfs.LinkHandle;
import org.drftpd.vfs.ObjectNotValidException;
import org.drftpd.vfs.VirtualFileSystem;
import org.tanesha.replacer.FormatterException;
import org.tanesha.replacer.ReplacerEnvironment;
import org.tanesha.replacer.ReplacerFormat;
import org.tanesha.replacer.SimplePrintf;

/**
 * @author djb61
 * @version $Id$
 */
public class ZipscriptPostHook implements PostHookInterface {

	private static final Logger logger = Logger.getLogger(ZipscriptPostHook.class);

	private ResourceBundle _bundle;

	private String _keyPrefix;

	public void initialize(StandardCommandManager cManager) {
		_bundle = cManager.getResourceBundle();
    	_keyPrefix = this.getClass().getName()+".";
	}

	public void doZipscriptRETRPostCheck(CommandRequest request, CommandResponse response) {

		if (response.getCode() != 226) {
			// Transfer failed, abort checks
			return;
		}
		FileHandle transferFile;
		try {
			transferFile =  (FileHandle) response.getObject(DataConnectionHandler.TRANSFER_FILE);
		} catch (KeyNotFoundException e) {
			// We don't have a file, we shouldn't have ended up here but return anyway
			return;
		}
		String transferFileName = transferFile.getName();
		long checksum = response.getObjectLong(DataConnectionHandler.CHECKSUM);
		logger.debug("Running zipscript on retrieved file " + transferFileName +
				" with CRC of " + checksum);

		if (checksum != 0) {
			response.addComment("Checksum from transfer: " +
					Checksum.formatChecksum(checksum));

			//compare checksum from transfer to checksum from sfv
			try {
				ZipscriptVFSDataSFV sfvData = new ZipscriptVFSDataSFV(transferFile.getParent());
				SFVInfo sfv = sfvData.getSFVInfo();
				Long sfvChecksum = sfv.getEntries().get(transferFile.getName());

				if (sfvChecksum == null) {
					// No entry in sfv for this file so nothing to check against
				} else if (checksum == sfvChecksum) {
					response.addComment(
					"checksum from transfer matched checksum in .sfv");
				} else {
					response.addComment(
					"WARNING: checksum from transfer didn't match checksum in .sfv");
				}
			} catch (NoAvailableSlaveException e1) {
				response.addComment(
				"slave with .sfv offline, checksum not verified");
			} catch (FileNotFoundException e1) {
				//continue without verification
			}
		} else { // slave has disabled download crc

			//response.addComment("Slave has disabled download checksum");
		}
		return;
	}

	public void doZipscriptSTORPostCheck(CommandRequest request, CommandResponse response) {
		if (response.getCode() != 226) {
			// Transfer failed, abort checks
			return;
		}
		FileHandle transferFile;
		try {
			transferFile =  (FileHandle) response.getObject(DataConnectionHandler.TRANSFER_FILE);
		} catch (KeyNotFoundException e) {
			// We don't have a file, we shouldn't have ended up here but return anyway
			return;
		}
		String transferFileName = transferFile.getName();
		long checksum = response.getObjectLong(DataConnectionHandler.CHECKSUM);
		logger.debug("Running zipscript on stored file " + transferFileName +
				" with CRC of " + checksum);
		if (!transferFileName.toLowerCase().endsWith(".sfv")) {
			try {
				ZipscriptVFSDataSFV sfvData = new ZipscriptVFSDataSFV(transferFile.getParent());
				SFVInfo sfv = sfvData.getSFVInfo();
				Long sfvChecksum = sfv.getEntries().get(transferFile.getName());

				/*If no exceptions are thrown means that the sfv is avaible and has a entry
				 * for that file.
				 * With this certain, we can assume that files that have CRC32 = 0 either is a
				 * 0byte file (bug!) or checksummed transfers are disabled(size is different
				 * from 0bytes though).                 
				 */
				if (sfvChecksum == null) {
					// No entry in the sfv for this file, just return and allow
					response.addComment("zipscript - no entry in sfv for file");
				} else if (checksum == sfvChecksum) {
					// Good! transfer checksum matches sfv checksum
					response.addComment("checksum match: SLAVE/SFV:" +
							Long.toHexString(checksum));
				} else if (checksum == 0) {
					// Here we have two conditions:
					if (transferFile.getSize() == 0) {
						// The file has checksum = 0 and the size = 0 
						// then it should be deleted.
						response.addComment("0Byte File, Deleting...");
						transferFile.delete();
					} else
						// The file has checksum = 0, although the size is != 0,
						// meaning that we are not using checked transfers.
						response.addComment("checksum match: SLAVE/SFV: DISABLED");
				} else {
					response.addComment("checksum mismatch: SLAVE: " +
							Long.toHexString(checksum) + " SFV: " +
							Long.toHexString(sfvChecksum));
					response.addComment(" deleting file");
					response.setMessage("Checksum mismatch, deleting file");
					transferFile.delete();

					//				getUser().updateCredits(
					//					- ((long) getUser().getRatio() * transferedBytes));
					//				getUser().updateUploadedBytes(-transferedBytes);
					// response.addComment(conn.status());
				}
			} catch (NoAvailableSlaveException e) {
				response.addComment(
				"zipscript - SFV unavailable, slave(s) with .sfv file is offline");
			} catch (FileNotFoundException e) {
				response.addComment(
						"zipscript - SFV unavailable, IO error: " +
						e.getMessage());
			}
		}
		return;
	}

	public void doZipscriptCWDStatsHook(CommandRequest request, CommandResponse response) {
		if (response.getCode() != 250) {
			// CWD failed, abort stats
			return;
		}
		Properties cfg =  GlobalContext.getGlobalContext().getPluginsConfig().
		getPropertiesForPlugin("zipscript.conf");
		if (cfg.getProperty("cwd.racestats.enabled").equals("true")) {
			addRaceStats(request, response);
		}
	}

	public void doZipscriptSTORStatsHook(CommandRequest request, CommandResponse response) {
		if (response.getCode() != 226) {
			// STOR failed, abort stats
			return;
		}
		Properties cfg =  GlobalContext.getGlobalContext().getPluginsConfig().
		getPropertiesForPlugin("zipscript.conf");
		if (cfg.getProperty("stor.racestats.enabled").equals("true")) {
			addRaceStats(request, response);
		}
	}

	public void doZipscriptSTORIncompleteHook(CommandRequest request, CommandResponse response) {
		if (response.getCode() != 226) {
			// STOR failed, abort link
			return;
		}
		FileHandle transferFile;
		try {
			transferFile =  (FileHandle) response.getObject(DataConnectionHandler.TRANSFER_FILE);
		} catch (KeyNotFoundException e) {
			// We don't have a file, we shouldn't have ended up here but return anyway
			return;
		}
		String transferFileName = transferFile.getName();
		if (transferFileName.toLowerCase().endsWith(".sfv")) {
			LinkUtils.processLink(request, "create", _bundle);
		}
		else {
			ZipscriptVFSDataSFV sfvData = new ZipscriptVFSDataSFV(request.getCurrentDirectory());
			try {
				SFVStatus sfvStatus = sfvData.getSFVStatus();
				if (sfvStatus.isFinished()) {
					// dir is complete, remove link
					LinkUtils.processLink(request, "delete", _bundle);
				}
			} catch (NoAvailableSlaveException e) {
				// Slave holding sfv is unavailable
			} catch (FileNotFoundException e) {
				// No sfv in dir
			}
		}
		return;
	}

	public void doZipscriptDELECleanupHook(CommandRequest request, CommandResponse response) {
		if (response.getCode() != 250) {
			// DELE failed, abort cleanup
			return;
		}
		FileHandle deleFile;
		try {
			deleFile =  (FileHandle) response.getObject(Dir.DELEFILE);
		} catch (KeyNotFoundException e) {
			// We don't have a file, we shouldn't have ended up here but return anyway
			return;
		}
		String deleFileName = deleFile.getName();
		if (deleFileName.endsWith(".sfv")) {
			LinkUtils.processLink(request, "delete", _bundle);
			try {
				ZipscriptVFSDataSFV sfvData = new ZipscriptVFSDataSFV(request.getCurrentDirectory());
				sfvData.removeSFVInfo();
			} catch(FileNotFoundException e) {
				// No inode to remove sfvinfo from
			}
		}
		else {
			ZipscriptVFSDataSFV sfvData = new ZipscriptVFSDataSFV(request.getCurrentDirectory());
			try {
				SFVStatus sfvStatus = sfvData.getSFVStatus();
				if (!sfvStatus.isFinished()) {
					// dir is now incomplete, add link
					LinkUtils.processLink(request, "create", _bundle);
				}
			} catch (NoAvailableSlaveException e) {
				// Slave holding sfv is unavailable
			} catch (FileNotFoundException e) {
				// No sfv in dir
			}
		}
		return;
	}

	public void doZipscriptWIPECleanupHook(CommandRequest request, CommandResponse response) {
		if (response.getCode() != 200) {
			// WIPE failed, abort cleanup
			return;
		}
		try {
			for (LinkHandle link : request.getCurrentDirectory().getLinks()) {
				try {
					link.getTargetDirectory().getPath();
				} catch (FileNotFoundException e1) {
					// Link target no longer exists, remote it
					link.delete();
				} catch (ObjectNotValidException e1) {
					// Link target isn't a directory, delete the link as it is bad
					link.delete();
					continue;
				}
			}
		} catch (FileNotFoundException e2) {
			logger.warn("Invalid link in dir " + request.getCurrentDirectory().getPath(),e2);
		}
		// Have to check parent too to allow for the case of wiping a special subdir
		try {
			for (LinkHandle link : request.getCurrentDirectory().getParent().getLinks()) {
				try {
					link.getTargetDirectory().getPath();
				} catch (FileNotFoundException e1) {
					// Link target no longer exists, remote it
					link.delete();
				} catch (ObjectNotValidException e1) {
					// Link target isn't a directory, delete the link as it is bad
					link.delete();
					continue;
				}
			}
		} catch (FileNotFoundException e2) {
			logger.warn("Invalid link in dir " + request.getCurrentDirectory().getParent().getPath(),e2);
		}
	}

	private void addRaceStats(CommandRequest request, CommandResponse response) {
		// show race stats
		try {
			DirectoryHandle dir = request.getCurrentDirectory();
			ZipscriptVFSDataSFV sfvData = new ZipscriptVFSDataSFV(dir);
			SFVInfo sfvInfo = sfvData.getSFVInfo();
			SFVStatus sfvStatus = sfvData.getSFVStatus();
			Collection<UploaderPosition> racers = RankUtils.userSort(getSFVFiles(dir, sfvData),
					"bytes", "high");
			Collection<GroupPosition> groups = RankUtils.topFileGroup(getSFVFiles(dir, sfvData));

			String racerline = _bundle.getString(_keyPrefix+"cwd.racers.body");
			String groupline = _bundle.getString(_keyPrefix+"cwd.groups.body");

			ReplacerEnvironment env = request.getSession().getReplacerEnvironment(null,
					request.getSession().getUserNull(request.getUser()));

			//Start building race message
			String racetext = _bundle.getString(_keyPrefix+"cwd.racestats.header") + "\n";
			racetext += _bundle.getString(_keyPrefix+"cwd.racers.header") + "\n";

			ReplacerFormat raceformat = null;

			//Add racer stats
			int position = 1;

			for (UploaderPosition stat : racers) {
				User raceuser;

				try {
					raceuser = GlobalContext.getGlobalContext().getUserManager()
					.getUserByName(stat.getUsername());
				} catch (NoSuchUserException e2) {
					continue;
				} catch (UserFileException e2) {
					logger.log(Level.FATAL, "Error reading userfile", e2);

					continue;
				}

				ReplacerEnvironment raceenv = new ReplacerEnvironment();

				raceenv.add("speed",
						Bytes.formatBytes(stat.getXferspeed()) + "/s");
				raceenv.add("user", stat.getUsername());
				raceenv.add("group", raceuser.getGroup());
				raceenv.add("files", "" + stat.getFiles());
				raceenv.add("bytes", Bytes.formatBytes(stat.getBytes()));
				raceenv.add("position", String.valueOf(position));
				raceenv.add("percent",
						Integer.toString(
								(stat.getFiles() * 100) / sfvInfo.getSize()) + "%");

				try {
					racetext += (SimplePrintf.jprintf(racerline,
							raceenv) + "\n");
					position++;
				} catch (FormatterException e) {
					logger.warn(e);
				}
			}

			racetext += _bundle.getString(_keyPrefix+"cwd.racers.footer") + "\n";
			racetext += _bundle.getString(_keyPrefix+"cwd.groups.header") + "\n";

			//add groups stats
			position = 1;

			for (GroupPosition stat: groups) {
				ReplacerEnvironment raceenv = new ReplacerEnvironment();

				raceenv.add("group", stat.getGroupname());
				raceenv.add("position", String.valueOf(position));
				raceenv.add("bytes", Bytes.formatBytes(stat.getBytes()));
				raceenv.add("files", Integer.toString(stat.getFiles()));
				raceenv.add("percent",
						Integer.toString(
								(stat.getFiles() * 100) / sfvInfo.getSize()) + "%");
				raceenv.add("speed",
						Bytes.formatBytes(stat.getXferspeed()) + "/s");

				try {
					racetext += (SimplePrintf.jprintf(groupline,
							raceenv) + "\n");
					position++;
				} catch (FormatterException e) {
					logger.warn(e);
				}
			}

			racetext += _bundle.getString(_keyPrefix+"cwd.groups.footer") + "\n";

			env.add("completefiles", Integer.toString(sfvStatus.getPresent()));
			env.add("totalfiles", Integer.toString(sfvInfo.getSize()));
			env.add("totalbytes", Bytes.formatBytes(getSFVTotalBytes(dir, sfvData)));
			env.add("totalspeed",
					Bytes.formatBytes(getXferspeed(dir, sfvData)) + "/s");
			env.add("totalpercent",
					Integer.toString(
							(sfvStatus.getPresent() * 100) / sfvInfo.getSize()) +
			"%");

			racetext += _bundle.getString(_keyPrefix+"cwd.totals.body") + "\n";
			racetext += _bundle.getString(_keyPrefix+"cwd.racestats.footer") + "\n";

			try {
				raceformat = ReplacerFormat.createFormat(racetext);
			} catch (FormatterException e1) {
				logger.warn(e1);
			}

			try {
				if (raceformat == null) {
					response.addComment("cwd.uploaders");
				} else {
					response.addComment(SimplePrintf.jprintf(raceformat, env));
				}
			} catch (FormatterException e) {
				response.addComment("cwd.uploaders");
				logger.warn("", e);
			}
		} catch (RuntimeException ex) {
			logger.error("", ex);
		} catch (FileNotFoundException e) {
			//Error fetching SFV, ignore
		} catch (NoAvailableSlaveException e) {
			//Error fetching SFV, ignore
		}
	}

	/* TODO The following methods would be much better suited elsewhere,
	 * most likely in SFVInfo, however placing them there at this time
	 * would create dependency problems with the slave plugin.
	 * They can be moved once the sfv retrieval code in the slave is
	 * abstracted out removing the dependency of the slave code on
	 * the SFVInfo class.
	 */

	private Collection<FileHandle> getSFVFiles(DirectoryHandle dir, ZipscriptVFSDataSFV sfvData) 
	throws FileNotFoundException, NoAvailableSlaveException {
		Collection<FileHandle> files = new ArrayList<FileHandle>();
		SFVInfo sfvInfo = sfvData.getSFVInfo();

		for (String name : sfvInfo.getEntries().keySet()) {
			FileHandle file = new FileHandle(dir.getPath()+VirtualFileSystem.separator+name);
			if (file.exists()) {
				files.add(file);
			}
		}
		return files;
	}

	private long getSFVTotalBytes(DirectoryHandle dir, ZipscriptVFSDataSFV sfvData) 
	throws FileNotFoundException, NoAvailableSlaveException {
		long totalBytes = 0;

		for (FileHandle file : getSFVFiles(dir, sfvData)) {
			if (file.getXfertime() != -1) {
				totalBytes += file.getSize();
			}
		}
		return totalBytes;
	}

	private long getSFVTotalXfertime(DirectoryHandle dir, ZipscriptVFSDataSFV sfvData)
	throws FileNotFoundException, NoAvailableSlaveException {
		long totalXfertime = 0;

		for (FileHandle file : getSFVFiles(dir, sfvData)) {
			if (file.getXfertime() != -1) {
				totalXfertime += file.getXfertime();
			}
		}
		return totalXfertime;
	}

	private long getXferspeed(DirectoryHandle dir, ZipscriptVFSDataSFV sfvData)
	throws FileNotFoundException, NoAvailableSlaveException {
		long totalXfertime = getSFVTotalXfertime(dir, sfvData);
		if (totalXfertime / 1000 == 0) {
			return 0;
		}

		return getSFVTotalBytes(dir, sfvData) / (totalXfertime / 1000);
	}
}
