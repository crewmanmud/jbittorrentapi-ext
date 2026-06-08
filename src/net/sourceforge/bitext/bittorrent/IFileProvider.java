package net.sourceforge.bitext.bittorrent;

import java.io.File;
import java.io.IOException;

public interface IFileProvider {
	void closeAllFiles() throws IOException;
	void addFile(String name, Integer length);
	boolean setDownloadDir(File dir);
	File getDownloadDir();
	void savePiece(Piece p) throws IOException;
	byte[] loadPieceData(Piece p) throws IOException;
}
