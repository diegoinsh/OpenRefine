/*
Copyright 2010, Google Inc.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,           
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY           
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

*/

package com.google.refine;

import java.awt.Desktop;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.net.URI;

import javax.swing.JFrame;

import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main class for the Refine server application. The HTTP container is now Solon (Jetty has been removed): this class
 * only sets the platform properties and delegates to {@link RefineSolon}.
 *
 * <p>The fully qualified name of this class is referenced by the packaging (launch4j {@code mainClass},
 * {@code refine.bat}/{@code refine} and {@code jinrefine.l4j.ini}), so it is kept unchanged.
 */
public class Refine {

    final static Logger logger = LoggerFactory.getLogger("refine");

    public static void main(String[] args) throws Exception {

        System.setProperty("VERBOSE", "false");

        // tell macosx to keep the menu associated with the screen and what the app title is
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("com.apple.eawt.CocoaComponent.CompatibilityMode", "false");
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", "JinRefine");

        // 容器装配、容器语义对齐与 headless/浏览器行为均见 RefineSolon（阻塞至服务结束）
        RefineSolon.main(args);
    }

    /**
     * Returns the Refine data directory, creating it (and the extensions directory) if needed.
     *
     * <p>Lifted from the former Jetty-based {@code RefineServer.getDataDir()} so that {@link RefineSolon} can inject the
     * {@code refine.data} / {@code butterfly.modules.path} init-params and the servlet context temp dir.
     */
    static String getDataDir() {
        String data_dir = Configurations.get("refine.data_dir");
        if (data_dir != null) {
            return data_dir;
        }

        File dataDir = null;
        File extensionsDir = null;
        File grefineDir = null;
        File gridworksDir = null;

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("windows")) {
            File parentDir = null;
            String appData = System.getenv("APPDATA");
            if (appData != null && appData.length() > 0) {
                // e.g., C:\Users\[userid]\AppData\Roaming
                parentDir = new File(appData);
            } else {
                // TODO migrate to System.getProperty("user.home")?
                String userProfile = System.getProperty("user.home");
                if (userProfile != null && userProfile.length() > 0) {
                    // e.g., C:\Users\[userid]
                    parentDir = new File(userProfile);
                }
            }

            if (parentDir == null) {
                parentDir = new File(".");
            }

            dataDir = new File(parentDir, "JinRefine");
            extensionsDir = new File(dataDir, "extensions");
            grefineDir = new File(new File(parentDir, "Google"), "Refine");
            gridworksDir = new File(parentDir, "Gridworks");
        } else if (os.contains("os x")) {
            // on macosx, use "~/Library/Application Support"
            String home = System.getProperty("user.home");

            String data_home = (home != null) ? home + "/Library/Application Support/JinRefine" : ".jinrefine";
            dataDir = new File(data_home);

            String grefine_home = (home != null) ? home + "/Library/Application Support/Google/Refine" : ".google-refine";
            grefineDir = new File(grefine_home);

            String gridworks_home = (home != null) ? home + "/Library/Application Support/Gridworks" : ".gridworks";
            gridworksDir = new File(gridworks_home);

            String extensions_home = (home != null) ? home + "/Library/Application Support/JinRefine/extensions"
                    : ".jinrefine/extensions";
            extensionsDir = new File(extensions_home);
        } else { // most likely a UNIX flavor
            // start with the XDG environment
            // see http://standards.freedesktop.org/basedir-spec/basedir-spec-latest.html
            String data_home = System.getenv("XDG_DATA_HOME");
            if (data_home == null) { // if not found, default back to ~/.local/share
                String home = System.getProperty("user.home");
                if (home == null) {
                    home = ".";
                }
                data_home = home + "/.local/share";
            }

            dataDir = new File(data_home + "/jinrefine");
            extensionsDir = new File(data_home + "/jinrefine/extensions");
            grefineDir = new File(data_home + "/google/refine");
            gridworksDir = new File(data_home + "/gridworks");
        }

        // If refine data dir doesn't exist, try to find and move Google Refine or Gridworks data dir over
        if (!dataDir.exists()) {
            if (grefineDir.exists()) {
                if (gridworksDir.exists()) {
                    logger.warn("Found both Gridworks: " + gridworksDir
                            + " & Googld Refine dirs " + grefineDir);
                }
                if (grefineDir.renameTo(dataDir)) {
                    logger.info("Renamed Google Refine directory " + grefineDir
                            + " to " + dataDir);
                } else {
                    logger.error("FAILED to rename Google Refine directory "
                            + grefineDir
                            + " to " + dataDir);
                }
            } else if (gridworksDir.exists()) {
                if (gridworksDir.renameTo(dataDir)) {
                    logger.info("Renamed Gridworks directory " + gridworksDir
                            + " to " + dataDir);
                } else {
                    logger.error("FAILED to rename Gridworks directory "
                            + gridworksDir
                            + " to " + dataDir);
                }
            }
        }

        // Either rename failed or nothing to rename - create a new one
        if (!dataDir.exists()) {
            logger.info("Creating new workspace directory " + dataDir);
            if (!dataDir.mkdirs()) {
                logger.error("FAILED to create new workspace directory " + dataDir);
            }
        }

        if (!extensionsDir.exists()) {
            if (!extensionsDir.mkdirs()) {
                logger.error("FAILED to create new extensions directory " + extensionsDir);
            }
        }

        return dataDir.getAbsolutePath();
    }
}

/* -------------- Refine Client ----------------- */

class RefineClient extends JFrame implements ActionListener {

    private static final long serialVersionUID = 7886547342175227132L;

    final static Logger logger = LoggerFactory.getLogger("refine-client");

    private URI uri;

    public void init(String host, int port, String contextPath) throws Exception {
        uri = new URI("http://" + host + ":" + port + contextPath);
        openBrowser();
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        String item = e.getActionCommand();
        if (item.startsWith("Open")) {
            openBrowser();
        }
    }

    private void openBrowser() {
        if (!Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) || System.getenv("SNAP") != null) {
            try {
                openBrowserFallback();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            try {
                Desktop.getDesktop().browse(uri);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void openBrowserFallback() throws IOException {
        Runtime rt = Runtime.getRuntime();

        if (SystemUtils.IS_OS_WINDOWS) {
            rt.exec(new String[] { "rundll32 ", "url.dll,FileProtocolHandler ", String.valueOf(uri) });
        } else if (SystemUtils.IS_OS_MAC_OSX) {
            rt.exec(new String[] { "open ", String.valueOf(uri) });
        } else if (SystemUtils.IS_OS_LINUX) {
            rt.exec(new String[] { "xdg-open ", String.valueOf(uri) });
        } else {
            logger.warn("Java Desktop class not supported on this platform. Please open {} in your browser", uri.toString());
        }
    }
}
