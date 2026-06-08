/*
 * Java Bittorrent API as its name indicates is a JAVA API that implements the Bittorrent Protocol
 * This project contains two packages:
 * 1. jBittorrentAPI is the "client" part, i.e. it implements all classes needed to publish
 *    files, share them and download them.
 *    This package also contains example classes on how a developer could create new applications.
 * 2. trackerBT is the "tracker" part, i.e. it implements a all classes needed to run
 *    a Bittorrent tracker that coordinates peers exchanges. *
 *
 * Copyright (C) 2007 Baptiste Dubuis, Artificial Intelligence Laboratory, EPFL
 *
 * This file is part of jbittorrentapi-v1.0.zip
 *
 * Java Bittorrent API is free software and a free user study set-up;
 * you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Java Bittorrent API is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Java Bittorrent API; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 * @version 1.0
 * @author Baptiste Dubuis
 * To contact the author:
 * email: baptiste.dubuis@gmail.com
 *
 * More information about Java Bittorrent API:
 *    http://sourceforge.net/projects/bitext/
 */

package net.sourceforge.bitext;

import java.util.ArrayList;

import net.sourceforge.bitext.bittorrent.ConnectionManager;
import net.sourceforge.bitext.bittorrent.DownloadManager;
import net.sourceforge.bitext.bittorrent.IOManager;
import net.sourceforge.bitext.bittorrent.TorrentFile;
import net.sourceforge.bitext.bittorrent.TorrentProcessor;
import net.sourceforge.bitext.utils.Utils;

/**
 *
 */
public class BitExt {

	/**
	 * @param args
	 *            No arguments should be provided
	 */
	public static void main(String[] args) {

		// Client id:
		byte[] myID = Utils.generateID();
		System.out.println("--------------------------------");
		System.out.println("| Extending BitTorrent Project |");
		System.out.println("--------------------------------\r\n");
		System.out.println("Client ID = " + new String(myID) + "\r\n\r\n");

		String userInput = "";

		if (IOManager
				.readUserInput("What you want to do?\r\n\t1) Publish file\r\n\t2) Retrieve file\r\nYour choice : ")
				.matches("1")) {

			System.out.println("Publishing new files...\r\n");

			TorrentProcessor tp = new TorrentProcessor();
			ArrayList<String> files = new ArrayList<String>();
			System.out.println("Enter the path of files you want to publish, no entry means you're done...");
			do {
				userInput = IOManager.readUserInput("File to publish: ");
				if (userInput.matches(""))
					break;
				else
					files.add(userInput);
			} while (true);
			try {
				if (files.size() > 1)
					tp.setTorrentData(
							IOManager
									.readUserInput("Enter tracker announce url: "),
							Integer.parseInt(IOManager
									.readUserInput("Enter piece length: ")),
							IOManager
									.readUserInput("Enter comment for your torrent: "),
							"UTF8",
							IOManager
									.readUserInput("Enter the name of the directory your files will be saved in: "),
							files);
				else if (files.size() == 1)
					tp.setTorrentData(
							IOManager
									.readUserInput("Enter tracker announce url: "),
							Integer.parseInt(IOManager
									.readUserInput("Enter piece length: ")),
							IOManager
									.readUserInput("Enter comment for your torrent: "),
							"UTF8", (String) files.get(0));

				tp.generatePieceHashes();
				IOManager.save(
						tp.generateTorrent(),
						(userInput = IOManager
								.readUserInput("Save torrent as: ")));
				// ConnectionManager.publish(userInput, "localhost", "", "",
				// "test.torrent", "noInfo", "MyComment", "7");
			} catch (Exception e) {
				e.printStackTrace();
			}

		} else {
			System.out.println("Retrieving files...\r\n");
			String host = "releases.ubuntu.com";
			int port = 80;
			String filename = "/maverick/ubuntu-10.10-server-i386.iso.torrent";
			String rename = "ubuntu.torrent";

			boolean startDownload = false;

			if ((userInput = IOManager
					.readUserInput("Do you want to download a torrent or "
							+ "use an existing one?\r\n1. Download\r\n2. Use existing\r\nYour choice: "))
					.matches("1")) {

				if (!(userInput = IOManager
						.readUserInput("Please enter host name [default = releases.ubuntu.com] : "))
						.matches(""))
					host = userInput;
				if (!(userInput = IOManager
						.readUserInput("Please enter host port [default = 80] :"))
						.matches(""))
					port = new Integer(userInput).intValue();
				if (!(userInput = IOManager
						.readUserInput("Please enter path of the file to download "
								+ "[default = /maverick/ubuntu-10.10-server-i386.iso.torrent]:"))
						.matches(""))
					filename = userInput;
				if (!(userInput = IOManager
						.readUserInput("Save this file as [default = ubuntu.torrent] :"))
						.matches(""))
					rename = userInput;

				if (!(userInput = IOManager
						.readUserInput("Start download? yes/no [default = no]"))
						.matches("")) {
					if (userInput.equalsIgnoreCase("yes")
							|| userInput.equalsIgnoreCase("y"))
						startDownload = true;
				}

				// Download torrent file according to given information
				ConnectionManager.downloadFile(host, port, filename, rename);
			} else {
				rename = IOManager.readUserInput("Enter path to torrent: ");
			}

			// Process the torrent file to extract features
			try {
				TorrentProcessor tp = new TorrentProcessor();
				TorrentFile t = tp.getTorrentFile(tp.parseTorrent(rename));
				System.out.println("Torrent parsed...");
				if (t != null && startDownload) {
					DownloadManager dm = new DownloadManager(t, myID);
					System.out.println("DM initiated...");
					dm.startListening(6881, 6889);
					System.out.println("Listening started...");
					dm.startTrackerUpdate();
					System.out.println("Updater started...");
					dm.blockUntilCompletion();
					dm.stopTrackerUpdate();
					dm.closeTempFiles();
				} else {
					System.err
							.println("Provided file is not a valid torrent file");
					System.err.flush();
					System.exit(1);
				}
			} catch (Exception e) {

				System.out.println("Error while processing torrent file");
				e.printStackTrace();
				System.exit(2);
			}

		}
	}
}
