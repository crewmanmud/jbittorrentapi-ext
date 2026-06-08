package net.sourceforge.bitext.bittorrent;

import java.util.ArrayList;

public interface IPieceACL {
	boolean isAuthorisedForPiece(String peerId, int pieceId);
	
	ArrayList<Integer> getAuthorisedPieces(String peerId);
}
