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
package org.drftpd.tools.ant;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

/**
 * @author djb61
 * @version $Id$
 */
public class ResourceTask extends Task {

	public static final boolean isWin32 = System.getProperty("os.name").startsWith("Windows");

	private File _baseDir;
	private File _resourceDir;
	private long _longDate = 0L;

	/**
	 * @param aBaseDir base directory for project
	 */
	public final void setBaseDir(final File aBaseDir) {
		_baseDir = aBaseDir;
	}

	/**
	 * @param aResourceDir base directory for resources
	 */
	public final void setResourceDir(final File aResourceDir) {
		_resourceDir = aResourceDir;
	}

	/**
	 * @see org.apache.tools.ant.Task#execute()
	 */
	@Override
	public void execute() throws BuildException {
		// Get the build start time as long
		SimpleDateFormat simpleBuildDate = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS");
		Date buildDate = null;
		try {
			buildDate = simpleBuildDate.parse(getProject().getProperty("build.plugins.start"));
		} catch (ParseException e) {
			throw new BuildException("Plugin build timestamp not set correctly");
		}
		_longDate = buildDate.getTime();
		findResources(_resourceDir);
	}

	/**
	 * Recursively scans a directory for resource files
	 * and adds their entries to the installed resource
	 * files.
	 * 
	 * @param dir directory to search for resources
	 * @throws BuildException
	 */
	private void findResources(File dir) throws BuildException {
		if (!dir.isDirectory())
			throw new BuildException(dir.getPath() + " is not a directory");

		for (File file : dir.listFiles()) {
			if (file.getName().startsWith(".")) {
				continue;
			} else if (file.isFile()) {
				copyResource(file);
			} else if (file.isDirectory()){
				findResources(file);
			}
		}
	}

	/**
	 * Copies a resource file into the installed directory
	 * hierarchy, if the file already exists and was created
	 * during this build session the contents are appended
	 * to the existing file, if the file is from an earlier
	 * build session it will be deleted and replaced
	 * 
	 * @param file resource file to be copied
	 */
	private void copyResource(File file) {
		String relativePath = file.getPath().substring(_resourceDir.getPath().length()+1);
		File newFile = new File(_baseDir, relativePath);
		// Check we have a dir for this resource, if not make it
		if (!newFile.getParentFile().exists()) {
			newFile.getParentFile().mkdirs();
		}
		// Delete stale file if needed
		if (newFile.lastModified() == 0L || newFile.lastModified() < _longDate) {
			// Safe to try a delete even if the file doesn't exist
			newFile.delete();
		}
		FileInputStream fis = null;
		BufferedReader input = null;
		StringBuilder output = new StringBuilder();
		try {
			// Create a BufferedReader to read the file
			fis = new FileInputStream(file);
			input = new BufferedReader(new InputStreamReader(fis));

			// Read all lines from file
			while (input.ready()) {
				output.append(input.readLine());
				output.append("\n");
			}
		} catch (FileNotFoundException e) {
			log("File appears to have been deleted, skipping: " + file.getName());
		} catch (IOException e) {
			log("Failed to load resources from: " + file.getName());
		} finally {
			try {
				input.close();
				fis.close();
			} catch (IOException e) {
				// FileInputStream is already closed
			}
		}
		// Write data to new file
		FileWriter outputWriter = null;
		try {
			outputWriter = new FileWriter(newFile,true);
			outputWriter.write(output.toString()+"\n");
			outputWriter.flush();
		} catch (FileNotFoundException e) {
			log("Cannot write resource file to: " + newFile.getParent());
		} catch (IOException e) {
			log("Error writing resource file: " + newFile.getName());
		} finally {
			if (outputWriter != null) {
				try {
					outputWriter.close();
				} catch (IOException e) {
					// Just means it doesn't need closing
				}
			}
		}
		// If non windows and a shell script then chmod
		if (!isWin32) {
			if (newFile.getName().endsWith(".sh")) {
				String[] cmdArray = {"chmod","755",newFile.getAbsolutePath()};
				try {
					Process p = Runtime.getRuntime().exec(cmdArray);
					p.waitFor();
					if (p.exitValue() != 0) {
						log("Error chmodding file: "+newFile.getAbsolutePath());
					}
				} catch (IOException e) {
					log("Error chmodding file: "+newFile.getAbsolutePath());
				} catch (InterruptedException e) {
					log("Chmod process was interrupted on file: "+newFile.getAbsolutePath());
				}
			}
		}
	}
}