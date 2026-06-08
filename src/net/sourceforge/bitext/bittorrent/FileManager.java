package net.sourceforge.bitext.bittorrent;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import net.sourceforge.bitext.utils.Utils;


public class FileManager implements IFileProvider {
	
	private final boolean USE_SEPARATE_PIECE_DIR;
	
	private static final String DEFAULT_DOWNLOAD_DIR = "download" + File.separatorChar;
	private static final String DEFAULT_PIECE_DIR = "pieces" + File.separatorChar;
	private static final String TMP_DIR = "tmp" + File.separatorChar;
	
	private static final String PIECE_FILE_EXT = ".piece";
	
	private ArrayList<String> fileNames;
	private ArrayList<Integer> fileLengths;
	
	private Map<Piece, RandomAccessFile> pieceFiles;
	
	private final TorrentFile torrent;
	
	private File downloadDir;
	
	protected FileManager(final TorrentFile t) {
		this(t, false);
	}
	
	protected FileManager(final TorrentFile t, final boolean usePieceDir) {
		
		USE_SEPARATE_PIECE_DIR = usePieceDir;
		
		torrent = t;
		int numberOfFiles = t.length.size();
		fileNames = new ArrayList<String>(numberOfFiles);
		fileLengths = new ArrayList<Integer>(numberOfFiles);
		
		int size = (int) Math.ceil((double)t.total_length/(double)t.pieceLength);
		pieceFiles = Collections.synchronizedMap(new HashMap<Piece, RandomAccessFile>(size));
		
		/*watchdog = new CreationTimeWatchdog();
		watchdog.start();*/
	}

	//TODO If reading and piece data file does not exist, create temp file from actual file.
	private RandomAccessFile getFile(final Piece p, Integer function) throws IOException {
		RandomAccessFile r;
		if (pieceFiles.containsKey(p)) {
			r = pieceFiles.get(p);
			return r;
		}
		
		File pieceFile = getPieceFile(p);
		if (function == ACCESS_READ && filesExist(p)) {
			//Read in piece from file.
			File parent = pieceFile.getParentFile();
			if (parent != null)
				Files.createDirectories(parent.toPath());
			Files.write(pieceFile.toPath(), readPieceDataFromFile(p));
		}
		
		r = new RandomAccessFile(pieceFile, "rw");
		pieceFiles.put(p, r);
		return r;
	}

	private File getPieceFile(Piece p) {
		String filename = Utils.bytesToHex(p.sha1) + PIECE_FILE_EXT;
		String path = (USE_SEPARATE_PIECE_DIR) ? (DEFAULT_PIECE_DIR + filename) : filename;
		return new File(getDownloadDir(), path);
	}
	
	private byte[] readPieceDataFromFile(final Piece p) throws IOException {
		byte[] data = new byte[p.getLength()];
		int remainingData = data.length;
		for (int file : p.getFileAndOffset().keySet()) {
			int remaining = this.torrent.length.get(file) - p.getFileAndOffset().get(file);
			
			File tmpFile = new File(getTempDir(), torrent.name.get(file));
			Files.copy(new File(getDownloadDir(), torrent.name.get(file)).toPath(),
					tmpFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			RandomAccessFile r = new RandomAccessFile(tmpFile, "r");
			r.seek(p.getFileAndOffset().get(file));
			r.read(data, data.length - remainingData, (remaining < remainingData) ? remaining : remainingData);
			
			r.close();
			tmpFile.delete();
			remainingData -= remaining;
		}
		return data;
	}
	
	private boolean filesExist(final Piece p) {
		for (Integer fileIndex : p.getFileAndOffset().keySet())
			if (!(new File(getDownloadDir(), torrent.name.get(fileIndex))).exists())
				return false;
		return true;
	}

	@Override
	public void closeAllFiles() throws IOException {
		synchronized(pieceFiles){
			for (RandomAccessFile r : pieceFiles.values()) {
				r.close();
			}
			pieceFiles.clear();
		}
	}

	/*private class CreationTimeWatchdog extends Thread {
		
		// Default timeout of 5 mins...
		private static final long DEFAULT_FILE_TIMEOUT = 5 * 60000;
		private static final long DEFAULT_WAIT = 60000;
		
		private boolean quit = false;
		
		private CreationTimeWatchdog() {
			//this.setDaemon(true);
		}
		
		public void run() {
			while (!quit) {
				try {
					Thread.sleep(DEFAULT_WAIT);
					
					for (int i = 0; i < creationTime.length; i++) {
						if (tempRandAccessFiles[i] != null && System.currentTimeMillis() - creationTime[i] > DEFAULT_FILE_TIMEOUT) {
							tempRandAccessFiles[i] = null;
							creationTime[i] = 0l;
							FileUtils.deleteQuietly(tempFiles[i]);
						}
					}
				} catch (InterruptedException e) {
					// Must be trying to shutdown!
				}
			}
		}
		
		public void shutdown() {
			quit = true;
			this.interrupt();
		}
	}*/

	@Override
	public void savePiece(Piece p) throws IOException {
		byte[] data = p.data();
		accessPieceFile(p, ACCESS_WRITE, data);
		
		//TODO Check if files are complete? Not important...
	}

	@Override
	public byte[] loadPieceData(Piece p) throws IOException {
		byte[] data = new byte[p.getLength()];
		//System.out.println("Empty hash: " + Utils.byteArrayToByteString(Utils.hash(data)));
		//RandomAccessFile r = getFile(p, ACCESS_READ);
		//r.read(data, 0, data.length);
		accessPieceFile(p, ACCESS_READ, data);
		//System.out.println("Populated?: " + Utils.byteArrayToByteString(Utils.hash(data)));
		return data;
	}

	private static final Integer ACCESS_READ = 0;
	private static final Integer ACCESS_WRITE = 1;
	
	//TODO If reading and piece data file does not exist, create temp file from actual file.
	private void accessPieceFile(final Piece p, final Integer function, byte[] data) throws IOException {
		if (function != ACCESS_READ && function != ACCESS_WRITE)
			throw new IllegalArgumentException("Value of 'function' has to be either read or write!");
		
		RandomAccessFile r = getFile(p, function);
		synchronized(r) {
			r.seek(0);
			if (function == ACCESS_READ) {
				r.read(data, 0, data.length);
			} else {
				r.write(data, 0, data.length);
			}
		}
	}

	@Override
	public boolean setDownloadDir(final File dir) {
		if (downloadDir != null)
			return false;
		
		downloadDir = dir;
		checkFolders();
		
		return true;
	}
	
	@Override
	public File getDownloadDir() {
		if (downloadDir == null)
			setDownloadDir(new File(DEFAULT_DOWNLOAD_DIR));
		
		return downloadDir;
	}
	
	public File getTempDir() {
		File tmpDir = new File(getDownloadDir(), TMP_DIR);
		if (!tmpDir.exists())
			tmpDir.mkdir();
		
		return tmpDir;
	}
	
	private void checkFolders() {
		if (!downloadDir.exists())
			downloadDir.mkdirs();
		
		if (USE_SEPARATE_PIECE_DIR) {
			File f = new File(getDownloadDir(), DEFAULT_PIECE_DIR);
			if (!f.exists())
				f.mkdir();
		}
	}

	@Override
	public void addFile(String name, Integer length) {
		synchronized(fileNames) {
			fileNames.add(name);
		}
		synchronized(fileLengths) {
			fileLengths.add(length);
		}
	}
}
