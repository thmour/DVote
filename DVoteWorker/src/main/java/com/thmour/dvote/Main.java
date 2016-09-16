/*
 * Copyright (C) 2016 theofilos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.thmour.dvote;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author theofilos
 */

public class Main {
    public static void main(String[] args) {
        String path = null;
        try {
            path = Main.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI().getPath();
            path = path.substring(0, path.lastIndexOf("/"));
        } catch (URISyntaxException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
            System.exit(1);
        }
        
        Server server;
        Properties p = new Properties();
        File conf = new File(path+"/config.properties");
        try {
            conf.createNewFile();
        } catch (IOException ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        try(InputStream in = new FileInputStream(conf)) {
            p.load(in);
            int port = Integer.valueOf(p.getProperty("worker.port", "8080"));
            String[] workers = p.getProperty("workers", "localhost").split(",");
            server = new Server(path, workers, port);
            server.start();
            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                server.stop();
                System.exit(1);
            }, 1, TimeUnit.DAYS);
        } catch (Exception ex) {
            Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
