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
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());
    
    public static void main(String[] args) {
        String path = null;
        try {
            path = Main.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI().getPath();
            path = path.substring(0, path.lastIndexOf("/"));
        } catch (URISyntaxException ex) {
            LOGGER.log(Level.SEVERE, null, ex);
            System.exit(1);
        }
        
        File conf = new File(path+"/config.properties");
        try {
            conf.createNewFile();
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE, null, ex);
        }
        
        Properties prop = new Properties();
        Server server;
        String[] workers;
        int replicationFactor, port, worker_port, candidates;
        try (FileInputStream fis = new FileInputStream(path+"/config.properties")) {
            prop.load(fis);
            replicationFactor = Integer.valueOf(prop.getProperty("replication", "1"));
            workers = prop.getProperty("workers", "localhost").split(",");
            port = Integer.valueOf(prop.getProperty("master.port", "9000"));
            worker_port = Integer.valueOf(prop.getProperty("worker.port", "9090"));
            candidates = Integer.valueOf(prop.getProperty("candidates", "3"));
            server = new Server(replicationFactor, workers, candidates, port, worker_port);
            server.start();
            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                server.stop();
                System.exit(0);
            }, 1, TimeUnit.DAYS);
        } catch(IOException ex) {
            LOGGER.log(Level.SEVERE, null, ex);
            System.exit(1);
        }
    }
}
