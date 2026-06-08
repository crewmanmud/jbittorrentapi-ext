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
 * Modifications copyright (C) 2011 Andrew McCallum
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

package net.sourceforge.bitext.bittorrent;

import java.util.*;
import java.io.*;
import java.net.Socket;


import net.sourceforge.bitext.utils.Utils;

/**
 * Object that manages all concurrent downloads. It chooses which piece to
 * request to which peer.
 */
public class DownloadManager implements DTListener, PeerUpdateListener, ConListenerInterface, IPieceACL, IFileProvider {

	// Client ID
	private byte[] clientID;

	private IPieceACL pieceAcl = this;
	
	private TorrentFile torrent = null;

	private int maxConnectionNumber = 100;

	private int nbOfFiles = 0;
	private long length = 0;
	private long left = 0;
	private Piece[] pieceList;
	private BitSet isComplete;
	private BitSet isRequested;
	private int nbPieces;
	private RandomAccessFile[] output_files;
	private IFileProvider fileMgr;

	private PeerUpdater pu = null;
	private ConnectionListener cl = null;

	private List<Peer> unchokeList = new LinkedList<Peer>();

	private LinkedHashMap<String, Peer> peerList = null;
	private TreeMap<String, DownloadTask> peerDownloadTasks = null;
	private LinkedHashMap<String, BitSet> peerAvailabilies = null;

	LinkedHashMap<String, Peer> unchoken = new LinkedHashMap<String, Peer>();
	private long lastTrackerContact = 0;
	private long lastUnchoking = 0;
	private short optimisticUnchoke = 3;

	private final String savePath;

	private volatile boolean quit = false;

	/**
	 * Create a new manager according to the given torrent and using the client
	 * id provided
	 * 
	 * @param torrent
	 *            TorrentFile
	 * @param clientID
	 *            byte[]
	 */
	public DownloadManager(TorrentFile torrent, final byte[] clientID) {
		this(torrent, clientID, null, null);
	}
	
	public DownloadManager(TorrentFile torrent, final byte[] clientID, String savePath) {
		this(torrent, clientID, savePath, null);
	}
	
	public DownloadManager(TorrentFile t, final byte[] clientID, String savePath, IPieceACL pieceAcl) {
		this.clientID = clientID;
		this.peerList = new LinkedHashMap<String, Peer>();
		this.peerDownloadTasks = new TreeMap<String, DownloadTask>();
		this.peerAvailabilies = new LinkedHashMap<String, BitSet>();

		this.torrent = t;
		this.nbPieces = t.piece_hash_values_as_binary.size();
		this.pieceList = new Piece[this.nbPieces];
		this.nbOfFiles = this.torrent.length.size();

		this.isComplete = new BitSet(nbPieces);
		this.isRequested = new BitSet(nbPieces);
		this.fileMgr = new FileManager(torrent);
		if (fileMgr == this)
			this.output_files = new RandomAccessFile[this.nbOfFiles];

		this.length = this.torrent.total_length;
		this.left = this.length;
		
		String tempPath = savePath;
		if (tempPath == null)
			tempPath = Constants.SAVEPATH;
		this.savePath = tempPath;
		
		if (pieceAcl != null)
			this.pieceAcl = pieceAcl;

		this.checkTempFiles();

		// Construct all the pieces with the correct length and hash value
		int file = 0;
		int fileoffset = 0;
		for (int i = 0; i < this.nbPieces; i++) {
			TreeMap<Integer, Integer> tm = new TreeMap<Integer, Integer>();
			int pieceoffset = 0;
			while (file <= (t.length.size()-1)) {
				tm.put(file, fileoffset);
				//TODO Need to correct for multiple files contained within one piece!
				if ((fileoffset + this.torrent.pieceLength - pieceoffset) >= t.length.get(file)) {
					pieceoffset += (t.length.get(file) - fileoffset);
					file++;
					fileoffset = 0;
					if (pieceoffset == this.torrent.pieceLength)
						break;
				} else {
					fileoffset += this.torrent.pieceLength - pieceoffset;
					break;
				}
			}
			//TODO Might have to fix arithmatic for final piece length....
			pieceList[i] = new Piece(i, 
					(i != this.nbPieces - 1) ? this.torrent.pieceLength	: ((Long) (this.length % this.torrent.pieceLength)).intValue(), 
							16384, (byte[]) t.piece_hash_values_as_binary.get(i), tm);
			
			if (this.testComplete(i)) {
				this.setComplete(i, true);
				this.left -= this.pieceList[i].getLength();
			}
		}
		this.lastUnchoking = System.currentTimeMillis();
		
		// If we're downloading the torrent then just write-through to the actual files...
		/*if (!this.isComplete())
			output_files.setWriteThrough(true);*/
	}

	public boolean testComplete(int piece) {
		boolean complete = false;
		this.pieceList[piece].setBlock(0, this.getPieceFromFiles(piece));
		complete = this.pieceList[piece].verify();
		this.pieceList[piece].clearData();
		return complete;
	}

	/**
	 * Periodically call the unchokePeers method. This is an infinite loop. User
	 * have to exit with Ctrl+C, which is not good... Todo is change this
	 * method...
	 */
	public void blockUntilCompletion() {
		byte[] b = new byte[0];

		while (!quit) {
			try {
				synchronized (b) {
					b.wait(10000);
					this.unchokePeers();
					b.notifyAll();

					if (this.isComplete())
						System.out.println("Sharing torrent \""+ torrent.comment + "\"...");
				}
			} catch (InterruptedException e) {
				// Trying to shutdown?
				continue;
			}
		}
	}

	/**
	 * Create and start the peer updater to retrieve new peers sharing the file
	 */
	public void startTrackerUpdate() {
		this.pu = new PeerUpdater(this.clientID, this.torrent);
		this.pu.addPeerUpdateListener(this);
		this.pu.setListeningPort(this.cl.getConnectedPort());
		this.pu.setLeft(this.left);
		this.pu.start();
	}

	/**
	 * Stop the tracker updates
	 */
	public void stopTrackerUpdate() {
		if (pu != null)
			this.pu.end();
	}

	/**
	 * Create the ConnectionListener to accept incoming connection from peers
	 * 
	 * @param minPort
	 *            The minimal port number this client should listen on
	 * @param maxPort
	 *            The maximal port number this client should listen on
	 * @return True if the listening process is started, false else
	 * @todo Should it really be here? Better create it in the implementation
	 */
	public boolean startListening(int minPort, int maxPort) {

		this.cl = new ConnectionListener();
		if (this.cl.connect(minPort, maxPort)) {
			this.cl.addConListenerInterface(this);
			return true;
		} else {
			System.err.println("Could not create listening socket...");
			System.err.flush();
			return false;
		}
	}

	/**
	 * Close all open files
	 */
	public void closeTempFiles() {
		try {
			fileMgr.closeAllFiles();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Check the existence of the files specified in the torrent and if
	 * necessary, create them
	 * 
	 * @return int
	 * @todo Should return an integer representing some error message...
	 */
	public synchronized int checkTempFiles() {
		//TODO Load in existing temp files... Maybe replace RAM files with actual?
		String saveas = savePath; //Constants.SAVEPATH;
		File rootDir;
		if (this.nbOfFiles > 1)
			saveas += this.torrent.saveAs + File.separator;
		
		(rootDir = new File(saveas)).mkdirs();
		
		fileMgr.setDownloadDir(rootDir);
		
		for (int i = 0; i < this.nbOfFiles; i++) {
			fileMgr.addFile(torrent.name.get(i), torrent.length.get(i));
		}
		return 0;
	}

	/**
	 * Save a piece in the corresponding file(s)
	 * 
	 * @param piece
	 *            int
	 */
	public synchronized void savePiece(int piece) {
		try {
			fileMgr.savePiece(pieceList[piece]);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		this.pieceList[piece].clearData();
	}

	/**
	 * Save the downloaded files into the corresponding directories
	 * 
	 * @deprecated
	 */
	public synchronized void save() {
		synchronized (this) {
			synchronized (this.isComplete) {
				byte[] data = new byte[0];
				for (int i = 0; i < this.nbPieces; i++) {
					if (this.pieceList[i] == null) {

					} else {
						data = Utils.concat(data, this.pieceList[i].data());
					}
				}
				String saveAs = savePath; //Constants.SAVEPATH;
				int offset = 0;
				if (this.nbOfFiles > 1)
					saveAs += this.torrent.saveAs + "/";
				for (int i = 0; i < this.nbOfFiles; i++) {
					try {
						new File(saveAs).mkdirs();
						FileOutputStream fos = new FileOutputStream(saveAs
								+ ((String) (this.torrent.name.get(i))));
						fos.write(Utils.subArray(data, offset, this.torrent.length.get(i)));
						fos.flush();
						fos.close();
						offset += this.torrent.length.get(i);
					} catch (IOException ioe) {
						ioe.printStackTrace();
						System.err.println("Error when saving the file "
								+ ((String) (this.torrent.name.get(i))));
					}
				}
			}
		}
	}

	/**
	 * Check if the current download is complete
	 * 
	 * @return boolean
	 */
	public synchronized boolean isComplete() {
		synchronized (this.isComplete) {
			return (this.isComplete.cardinality() == this.nbPieces);
		}
	}

	/**
	 * Returns the number of pieces currently requested to peers
	 * 
	 * @return int
	 */
	public synchronized int cardinalityR() {
		return this.isRequested.cardinality();
	}

	/**
	 * Returns the piece with the given index
	 * 
	 * @param index
	 *            The piece index
	 * @return Piece The piece with the given index
	 */
	public synchronized Piece getPiece(int index) {
		synchronized (this.pieceList) {
			return this.pieceList[index];
		}
	}

	/**
	 * Check if the piece with the given index is complete and verified
	 * 
	 * @param piece
	 *            The piece index
	 * @return boolean
	 */
	public synchronized boolean isPieceComplete(int piece) {
		synchronized (this.isComplete) {
			return this.isComplete.get(piece);
		}
	}

	/**
	 * Check if the piece with the given index is requested by a peer
	 * 
	 * @param piece
	 *            The piece index
	 * @return boolean
	 */
	public synchronized boolean isPieceRequested(int piece) {
		synchronized (this.isRequested) {
			return this.isRequested.get(piece);
		}
	}

	/**
	 * Mark a piece as complete or not according to the parameters
	 * 
	 * @param piece
	 *            The index of the piece to be updated
	 * @param is
	 *            True if the piece is now complete, false otherwise
	 */
	public synchronized void setComplete(int piece, boolean is) {
		synchronized (this.isComplete) {
			this.isComplete.set(piece, is);
		}
	}

	/**
	 * Mark a piece as requested or not according to the parameters
	 * 
	 * @param piece
	 *            The index of the piece to be updated
	 * @param is
	 *            True if the piece is now requested, false otherwise
	 */

	public synchronized void setRequested(int piece, boolean is) {
		synchronized (this.isRequested) {
			this.isRequested.set(piece, is);
		}
	}

	/**
	 * Returns a String representing the piece being requested by peers. Used
	 * only for pretty-printing.
	 * 
	 * @return String
	 */
	public synchronized String requestedBits() {
		String s = "";
		synchronized (this.isRequested) {
			for (int i = 0; i < this.nbPieces; i++)
				s += this.isRequested.get(i) ? 1 : 0;
		}
		return s;
	}

	/**
	 * Returns the index of the piece that could be downloaded by the peer in
	 * parameter
	 * 
	 * @param peerId
	 *            The id of the peer that wants to download
	 * @return int The index of the piece to request
	 */
	private synchronized int choosePiece2Download(String peerId) {
		// Return a piece that the peer would have and that the current
		// client is authorised for.
		synchronized (this.isComplete) {
			ArrayList<Integer> possible = pieceAcl.getAuthorisedPieces(peerId);
			// System.out.println(this.isRequested.cardinality()+" "+this.isComplete.cardinality()+" "
			// + possible.size());
			if (possible.size() > 0) {
				Random r = new Random(System.currentTimeMillis());
				int index = possible.get(r.nextInt(possible.size()));
				
				this.setRequested(index, true);
				return index;
			}
			return -1;
		}
	}

	/**
	 * Removes a task and peer after the task sends a completion message.
	 * Completion can be caused by an error (bad request, ...) or simply by the
	 * end of the connection
	 * 
	 * @param id
	 *            Task identity
	 * @param reason
	 *            Reason of the completion
	 */
	public synchronized void taskCompleted(String id, int reason) {
		switch (reason) {
		case DownloadTask.CONNECTION_REFUSED:
			System.err.println("Connection refused by host " + id);
			break;
		case DownloadTask.MALFORMED_MESSAGE:

			System.err.println("Malformed message from " + id);
			break;
		case DownloadTask.UNKNOWN_HOST:
			System.err.println("Connection could not be established to " + id);
		}
		
		this.peerAvailabilies.remove(id);
		this.peerDownloadTasks.remove(id);
		this.peerList.remove(id);
		// System.err.flush();
	}

	/**
	 * Received when a piece has been fully downloaded by a task. The piece
	 * might have been corrupted, in which case the manager will request it
	 * again later. If it has been successfully downloaded and verified, the
	 * piece status is set to 'complete', a 'HAVE' message is sent to all
	 * connected peers and the piece is saved into the corresponding file(s)
	 * 
	 * @param peerID
	 *            String
	 * @param i
	 *            int
	 * @param complete
	 *            boolean
	 */
	public synchronized void pieceCompleted(String peerID, int i,
			boolean complete) {
		synchronized (this.isRequested) {
			this.isRequested.clear(i);
		}
		synchronized (this.isComplete) {
			if (complete && !this.isPieceComplete(i)) {
				pu.updateParameters(this.torrent.pieceLength, 0, "");
				this.isComplete.set(i, complete);
				float totaldl = (float) (100.0f
						* ((float) (this.isComplete.cardinality())) / ((float) (this.nbPieces)));

				for (String s : peerDownloadTasks.keySet()) {
					try {
						this.peerDownloadTasks.get(s).messageSender
						.addMessageToQueue(new Message_PP(
								PeerProtocol.HAVE, Utils
								.intToByteArray(i), 1));
					} catch (NullPointerException npe) {
					}
				}
				System.out.println("Piece completed by " + peerID + " : " + i
						+ " (Total dl = " + totaldl + "% )");
				this.savePiece(i);
				// TODO Why do we need this? this.getPieceBlock(i, 0, 15000);

			} else {
				System.out.println("Piece completed but failed checks...?");
				// this.pieceList[i].data = new byte[0];
			}

			if (this.isComplete.cardinality() == this.nbPieces) {
				// System.out.println("Download completed, saving file...");
				// this.save();
				// this.task.clear();
				// this.end();
				System.out.println("Task completed");
				this.notify();
			}
		}
	}

	/**
	 * Set the status of the piece to requested or not
	 * 
	 * @param i
	 *            int
	 * @param requested
	 *            boolean
	 */
	public synchronized void pieceRequested(int i, boolean requested) {
		this.isRequested.set(i, requested);
	}

	/**
	 * Choose which of the connected peers should be unchoked and authorized to
	 * upload from this client. A peer gets unchoked if it is not interested, or
	 * if it is interested and has one of the 5 highest download rate among the
	 * interested peers. \r\n Every 3 times this method is called, calls the
	 * optimisticUnchoke method, which unchoke a peer no matter its download
	 * rate, in a try to find a better source
	 */
	private synchronized void unchokePeers() {
		synchronized (this.peerDownloadTasks) {
			int nbNotInterested = 0;
			int nbDownloaders = 0;
			int nbChoked = 0;
			this.unchoken.clear();
			
			List<Peer> l = new LinkedList<Peer>(this.peerList.values());
			if (!this.isComplete()) {
				Collections.sort(l, new DLRateComparator());
			} else {
				Collections.sort(l, new ULRateComparator());
			}

			for (Iterator<Peer> it = l.iterator(); it.hasNext();) {
				Peer p = (Peer) it.next();
				if (p.getDLRate(false) > 0)
					System.out.println(p + " rate: " + p.getDLRate(true)
							/ (1024 * 10) + "ko/s");

				DownloadTask dt = this.peerDownloadTasks.get(p.toString());
				if (nbDownloaders < 5 && dt != null) {
					if (!p.isInterested()) {
						this.unchoken.put(p.toString(), p);
						if (p.isChoked())
							dt.messageSender.addMessageToQueue(new Message_PP(
									PeerProtocol.UNCHOKE));
						p.setChoked(false);

						while (this.unchokeList.remove(p))
							;
						nbNotInterested++;
					} else if (p.isChoked()) {
						this.unchoken.put(p.toString(), p);
						dt.messageSender.addMessageToQueue(new Message_PP(
								PeerProtocol.UNCHOKE));
						p.setChoked(false);
						while (this.unchokeList.remove(p))
							;
						nbDownloaders++;
					}

				} else {
					if (!p.isChoked()) {
						dt.messageSender.addMessageToQueue(new Message_PP(
								PeerProtocol.CHOKE));
						p.setChoked(true);
					}
					if (!this.unchokeList.contains(p))
						this.unchokeList.add(p);
					nbChoked++;
				}
				p = null;
				dt = null;
			}
		}
		this.lastUnchoking = System.currentTimeMillis();
		if (this.optimisticUnchoke-- == 0) {
			this.optimisticUnchoke();
			this.optimisticUnchoke = 3;
		}
	}

	private synchronized void optimisticUnchoke() {
		if (!this.unchokeList.isEmpty()) {
			Peer p = null;
			do {
				p = (Peer) this.unchokeList.remove(0);
				synchronized (this.peerDownloadTasks) {
					DownloadTask dt = this.peerDownloadTasks.get(p.toString());
					if (dt != null) {
						dt.messageSender.addMessageToQueue(new Message_PP(
								PeerProtocol.UNCHOKE));
						p.setChoked(false);
						this.unchoken.put(p.toString(), p);
						System.out.println(p + " optimistically unchoken...");
					} else
						p = null;
					dt = null;
				}
			} while ((p == null) && (!this.unchokeList.isEmpty()));
			p = null;
		}
	}

	/**
	 * Received when a task is ready to download or upload. In such a case, if
	 * there is a piece that can be downloaded from the corresponding peer, then
	 * request the piece
	 * 
	 * @param peerId
	 *            String
	 */
	public synchronized void peerReady(String peerId) {
		if (System.currentTimeMillis() - this.lastUnchoking > 10000)
			this.unchokePeers();

		int requestedPiece;
		if ((requestedPiece = choosePiece2Download(peerId)) != -1) {
			this.peerDownloadTasks.get(peerId).requestPiece(this.pieceList[requestedPiece]);
		} else {
			//TODO No piece to download from peer - Throw exception or log?
			// No piece to download from peer!?
		}
	}

	/**
	 * Received when a peer requests a piece. If the piece is available (which
	 * should always be the case according to BitTorrent protocol) and we are
	 * able and willing to upload, then send the piece to the peer
	 * 
	 * @param peerID
	 *            String
	 * @param piece
	 *            int
	 * @param begin
	 *            int
	 * @param length
	 *            int
	 */
	public synchronized void peerRequest(String peerID, int piece, int begin, int length) {
		// Only allow seeding of pieces to peers who are allowed the piece.
		if (this.isPieceComplete(piece) && pieceAcl.isAuthorisedForPiece(peerID, piece)) {
			DownloadTask dt;
			if ((dt = this.peerDownloadTasks.get(peerID)) != null) {
				dt.messageSender.addMessageToQueue(new Message_PP(PeerProtocol.PIECE, 
						Utils.concat(Utils.intToByteArray(piece), 
								Utils.concat(Utils.intToByteArray(begin), 
										this.getPieceBlock(piece, begin, length)))));
				dt.peer.setULRate(length);
			}
			//TODO What function does the below serve?
			this.pu.updateParameters(0, length, "");
		} else {
			try {
				this.peerDownloadTasks.get(peerID).end();
			} catch (Exception e) { }
			this.peerDownloadTasks.remove(peerID);
			this.peerList.remove(peerID);
			this.unchoken.remove(peerID);
		}

	}

	/**
	 * Load piece data from the existing files
	 * 
	 * @param piece
	 *            int
	 * @return byte[]
	 */
	public synchronized byte[] getPieceFromFiles(int piece) {
		byte[] data = null;
		try {
			data = fileMgr.loadPieceData(pieceList[piece]);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return data;
	}

	/**
	 * Get a piece block from the existing file(s)
	 * 
	 * @param piece
	 *            int
	 * @param begin
	 *            int
	 * @param length
	 *            int
	 * @return byte[]
	 */
	public synchronized byte[] getPieceBlock(int piece, int begin, int length) {
		byte[] temp = this.getPieceFromFiles(piece);
		return Utils.subArray(temp, begin, length);
	}

	/**
	 * Update the piece availabilities for a given peer
	 * 
	 * @param peerID
	 *            String
	 * @param has
	 *            BitSet
	 */
	public synchronized void peerAvailability(String peerID, final BitSet has) {
		this.peerAvailabilies.put(peerID, has);
		BitSet interest = (BitSet) has.clone();
		interest.andNot(this.isComplete);
		
		DownloadTask dt;
		if ((dt = this.peerDownloadTasks.get(peerID)) != null) {
			if (interest.cardinality() > 0 && !dt.peer.isInteresting()) {
				dt.messageSender.addMessageToQueue(new Message_PP(
						PeerProtocol.INTERESTED, 2));
				dt.peer.setInteresting(true);
			}
		}
	}

	public synchronized void connect(Peer p) {
		DownloadTask dt = new DownloadTask(p, this.torrent.info_hash_as_binary,
				this.clientID, true, this.getBitField());
		dt.addDTListener(this);
		dt.start();
	}

	public synchronized void disconnect(Peer p) {
		DownloadTask dt = peerDownloadTasks.remove(p.toString());
		if (dt != null) {
			dt.end();
			dt = null;
		}
	}

	/**
	 * Given the list in parameter, check if the peers are already present in
	 * the peer list. If not, then add them and create a new task for them
	 * 
	 * @param list
	 *            LinkedHashMap
	 */
	public synchronized void updatePeerList(LinkedHashMap<String, Peer> list) {
		// this.lastUnchoking = System.currentTimeMillis();
		synchronized (this.peerDownloadTasks) {
			// this.peerList.putAll(list);
			Set<String> keyset = list.keySet();
			for (String key : keyset) {
				if (!this.peerDownloadTasks.containsKey(key)) {
					Peer p = list.get(key);
					this.peerList.put(p.toString(), p);
					this.connect(p);
				}
			}
		}
		if (list.size() > 0)
			System.out.println("Peer list updated with " + list.size() + " peers");
	}

	/**
	 * Called when an update try fail. At the moment, simply display a message
	 * 
	 * @param error
	 *            int
	 * @param message
	 *            String
	 */
	public void updateFailed(int error, String message) {
		System.err.println(message);
		System.err.flush();
	}

	/**
	 * Add the download task to the list of active (i.e. Handshake is ok) tasks
	 * 
	 * @param id
	 *            String
	 * @param dt
	 *            DownloadTask
	 */
	public synchronized void addActiveTask(String id, DownloadTask dt) {
		synchronized (this.peerDownloadTasks) {
			this.peerDownloadTasks.put(id, dt);
		}
	}

	/**
	 * Called when a new peer connects to the client. Check if it is already
	 * registered in the peer list, and if not, create a new DownloadTask for it
	 * 
	 * @param s
	 *            Socket
	 */
	public synchronized void connectionAccepted(Socket s) {
		synchronized (this.peerDownloadTasks) {

			String id = s.getInetAddress().getHostAddress() + ":" + s.getPort();
			if (!this.peerDownloadTasks.containsKey(id)) {
				DownloadTask dt = new DownloadTask(null,
						this.torrent.info_hash_as_binary, this.clientID, false,
						this.getBitField(), s);
				dt.addDTListener(this);
				this.peerList.put(dt.getPeer().toString(), dt.getPeer());
				this.peerDownloadTasks.put(dt.getPeer().toString(), dt);
				dt.start();
			}
		}
	}

	/**
	 * Compute the bitfield byte array from the isComplete BitSet
	 * 
	 * @return byte[]
	 */
	public byte[] getBitField() {
		int l = (int) Math.ceil((double) this.nbPieces / 8.0);
		byte[] bitfield = new byte[l];
		for (int i = 0; i < this.nbPieces; i++)
			if (this.isComplete.get(i)) {
				bitfield[i / 8] |= 1 << (7 - i % 8);
			}
		return bitfield;
	}

	/**
	 * Compute the percentage of completion.
	 * 
	 * @return float Percent complete.
	 */
	public float getCompleted() {
		return (100.0f * ((float) (this.isComplete.cardinality())) / ((float) (this.nbPieces)));
	}

	public float getDLRate() {
		try {
			float rate = 0.00f;
			List<Peer> l = new LinkedList<Peer>(this.peerList.values());

			for (Peer p : l) {
				if (p.getDLRate(false) > 0)
					rate = rate + p.getDLRate(true);
			}
			return rate / (1024 * 10);
		} catch (Exception e) {
			return 0.00f;
		}
	}

	public float getULRate() {
		try {
			float rate = 0.00f;
			List<Peer> l = new LinkedList<Peer>(this.peerList.values());

			for (Peer p : l) {
				if (p.getULRate(false) > 0)
					rate = rate + p.getULRate(true);
			}
			return rate / (1024 * 10);
		} catch (Exception e) {
			return 0.00f;
		}
	}

	@Override
	public ArrayList<Integer> getAuthorisedPieces(String peerId) {
		ArrayList<Integer> possible = new ArrayList<Integer>(this.nbPieces);
		for (int i = 0; i < this.nbPieces; i++) {
			if ((!this.isPieceRequested(i) || (this.isComplete.cardinality() > this.nbPieces - 3))
					&& (!this.isPieceComplete(i))
					&& this.peerAvailabilies.get(peerId) != null) {
				if (this.peerAvailabilies.get(peerId).get(i))
					possible.add(i);
			}
		}
		return possible;
	}

	@Override
	public boolean isAuthorisedForPiece(String peerId, int pieceId) {
		return true;
	}
	
	public void shutdown() {
		quit  = true;
		stopTrackerUpdate();
		Thread.currentThread().interrupt();
		for (String peerId : peerList.keySet()) {
			Peer p = peerList.get(peerId);
			disconnect(p);
			peerList.remove(peerId);
			p = null;
		}
		peerList.clear();
		peerAvailabilies.clear();
		cl.shutdown();
	}
	
	/*//                       //
	 // -- PRIVATE CLASSES -- //
	//                       //*/
	
	/**
	 * Compares 2 peers upload rate
	 */
	private final class ULRateComparator implements Comparator<Peer> {
	    /**
	     * Compares its two arguments for order.
	     *
	     * @param a the first object to be compared.
	     * @param b the second object to be compared.
	     * @return a negative integer, zero, or a positive integer as the first
	     * argument is less than, equal to, or greater than the second.
	     */
	    public int compare(Peer a, Peer b) {
	        if (a instanceof Peer && b instanceof Peer)
	            if (((Peer) a).getULRate(false) > ((Peer) b).getULRate(false))
	                return -1;
	            else if (((Peer) a).getULRate(false) < ((Peer) b).getULRate(false))
	                return 1;
	        return 0;
	    }
	}
	
	/**
	 * Compares 2 peers download rate
	 */
	private final class DLRateComparator implements Comparator<Peer> {
	    /**
	     * Compares its two arguments for order.
	     *
	     * @param a the first object to be compared.
	     * @param b the second object to be compared.
	     * @return a negative integer, zero, or a positive integer as the first
	     *   argument is less than, equal to, or greater than the second.
	     */
	    public int compare(Peer a, Peer b) {
	        if (a instanceof Peer && b instanceof Peer)
	            if (((Peer) a).getDLRate(false) > ((Peer) b).getDLRate(false))
	                return -1;
	            else if (((Peer) a).getDLRate(false) < ((Peer) b).getDLRate(false))
	                return 1;
	        return 0;
	    }
	}

	@Override
	public void closeAllFiles() throws IOException {
		for (int i = 0; i < this.output_files.length; i++) {
			this.output_files[i].close();
		}
	}

	@Override
	public boolean setDownloadDir(File dir) {
		// Unsupported...
		return false;
	}

	@Override
	public void savePiece(Piece p) throws IOException {
		byte[] data = p.data();
		int remainingData = data.length;
		for (int file : p.getFileAndOffset().keySet()) {
			int remaining = this.torrent.length.get(file) - p.getFileAndOffset().get(file);
			RandomAccessFile r = output_files[file];
			r.seek(p.getFileAndOffset().get(file));
			r.write(data, data.length - remainingData, (remaining < remainingData) ? remaining : remainingData);
					
			remainingData -= remaining;
		}
	}

	@Override
	public byte[] loadPieceData(Piece p) throws IOException {
		byte[] data = new byte[p.getLength()];
		int remainingData = data.length;
		for (int file : p.getFileAndOffset().keySet()) {
			int remaining = this.torrent.length.get(file) - p.getFileAndOffset().get(file);
			RandomAccessFile r = output_files[file];
			r.seek(p.getFileAndOffset().get(file));
			r.read(data, data.length - remainingData, (remaining < remainingData) ? remaining : remainingData);
			
			remainingData -= remaining;
		}
		return data;
	}

	@Override
	public void addFile(String name, Integer length) {
		int fileIndex = torrent.name.indexOf(name);
		
		File dwnldFile = new File(getDownloadDir(), name);
		// Ensure the parent folder(s) for the file have been created...
		File prntFolder = dwnldFile.getParentFile();
		if (prntFolder != null && !prntFolder.exists()) {
			prntFolder.mkdirs();
		}
		
		try {
			this.output_files[fileIndex] = new RandomAccessFile(dwnldFile, "rw");
			this.output_files[fileIndex].setLength(length);
			//output_files.setFile(i, dwnldFile, this.torrent.length.get(i));
		} catch (IOException ioe) {
			System.err.println("Could not create temp file. - " + dwnldFile);
			ioe.printStackTrace();
		}
	}

	@Override
	public File getDownloadDir() {
		return new File(savePath);
	}

}
