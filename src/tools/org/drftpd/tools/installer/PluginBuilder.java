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
package org.drftpd.tools.installer;

import java.io.File;
import java.io.IOException;
import java.io.PipedInputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;

import org.apache.log4j.Logger;
import org.apache.tools.ant.BuildEvent;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;
import org.apache.tools.ant.taskdefs.Ant.TargetElement;
import org.apache.tools.ant.taskdefs.SubAnt;
import org.apache.tools.ant.types.FileList;
import org.java.plugin.registry.PluginRegistry;

/**
 * @author djb61
 * @version $Id$
 */
public class PluginBuilder {

	private static final Logger logger = Logger.getLogger(PluginBuilder.class);
	private SubAnt _antBuilder = new SubAnt();
	private PluginBuildListener _pbListener;
	private Project _builderProject;

	public PluginBuilder(ArrayList<PluginData> toBuild, PluginRegistry registry, PipedInputStream logInput, InstallerConfig config, UserFileLocator locator) {
		// Sort selected plugins into correct order for building
		PluginTools.reorder(toBuild,registry);
		// Create a list of build files for the selected plugins
		StringBuilder buildFiles = new StringBuilder();
		Iterator<PluginData> iter = toBuild.iterator();
		while (iter.hasNext()) {
			File pluginFile = null;
			try {
				pluginFile = new File(iter.next().getDescriptor().getLocation().toURI());
				buildFiles.append(pluginFile.getParent().substring(System.getProperty("user.dir").length()+1)+File.separator+"build.xml");
				if (iter.hasNext()) {
					buildFiles.append(",");
				}
			} catch (URISyntaxException e) {
				logger.warn("Error loading plugin buildfile",e);
			}
		}

		// Set list of build files in the ant builder
		FileList fileList = new FileList();
		fileList.setFiles(buildFiles.toString());
		_antBuilder.addFilelist(fileList);

		// Create an ant Project and initialize default tasks/types
		_builderProject = new Project();
		_builderProject.init();

		// Read custom project wide config data and configure ant Project to use it
		File setupFile = new File(System.getProperty("user.dir")+File.separator+"setup.xml");
		ProjectHelper.configureProject(_builderProject,setupFile);

		// Add a custom build listener for logging and handling our additional needs
		_pbListener = new PluginBuildListener(logInput,config,toBuild,registry,locator);
		try {
			_pbListener.init();
		} catch (IOException e) {
			System.out.println(e);
		}
		_builderProject.addBuildListener(_pbListener);

		// Set installation dir if required
		if (!config.getInstallDir().equals("")) {
			_builderProject.setProperty("installdir", config.getInstallDir());
		} else {
			_builderProject.setProperty("installdir", System.getProperty("user.dir"));
		}

		// Set target(s)
		if (config.getClean()) {
			TargetElement cleanTarget = new TargetElement();
			cleanTarget.setName("clean");
			_antBuilder.addConfiguredTarget(cleanTarget);
		}
		TargetElement buildTarget = new TargetElement();
		buildTarget.setName("build");
		_antBuilder.addConfiguredTarget(buildTarget);

		// Final setup of ant builder
		_antBuilder.setProject(_builderProject);
		_antBuilder.setInheritall(true);
		_antBuilder.setFailonerror(true);
	}

	public void buildPlugins() {
		BuildException be = null;
		try {
			BuildEvent startEvent = new BuildEvent(_builderProject);
			startEvent.setMessage("BUILD STARTED",0);
			_pbListener.buildStarted(startEvent);
			_antBuilder.execute();
		} catch (BuildException e) {
			be = e;
		} finally {
			BuildEvent endEvent = new BuildEvent(_builderProject);
			if (be != null) {
				endEvent.setException(be);
			}
			_pbListener.buildFinished(endEvent);
			_pbListener.cleanup();
		}
	}
}
