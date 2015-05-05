package com.thilo.android.chessok;

import java.io.Serializable;
import java.util.List;

import com.thilo.android.chess.Link;

public class LinkList implements Serializable {
	private static final long serialVersionUID = -6273909436434463171L;
	private List<Link> linkList;

	public LinkList(List<Link> list) {
		this.setLinkList(list);
	}

	public void setLinkList(List<Link> linkList) {
		this.linkList = linkList;
	}

	public List<Link> getLinkList() {
		return linkList;
	}

	@Override
	public String toString() {
		return "LinkList [linkList=" + linkList + "]";
	}
}
