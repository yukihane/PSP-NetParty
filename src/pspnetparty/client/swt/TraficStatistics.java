/*
Copyright (C) 2011 monte

This file is part of PSP NetParty.

PSP NetParty is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package pspnetparty.client.swt;

import java.util.HashMap;

import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerSorter;
import org.eclipse.swt.graphics.Image;

import pspnetparty.lib.Utility;

public class TraficStatistics {

	public String macAddress;
	public boolean isMine;
	public String playerName = "";

	public long lastModified;
	public int currentInBytes;
	public int currentOutBytes;

	public double currentInKbps;
	public double currentOutKbps;

	public long totalInBytes;
	public long totalOutBytes;

	public TraficStatistics(String macAddress, boolean isMine) {
		this.macAddress = macAddress;
		this.isMine = isMine;
	}

	public void clearTotal() {
		totalInBytes = 0;
		totalOutBytes = 0;
	}

	public static class ContentProvider implements IStructuredContentProvider {

		@Override
		public void dispose() {
		}

		@Override
		public void inputChanged(Viewer arg0, Object arg1, Object arg2) {
		}

		@Override
		public Object[] getElements(Object input) {
			@SuppressWarnings("unchecked")
			HashMap<String, TraficStatistics> map = (HashMap<String, TraficStatistics>) input;
			return map.values().toArray();
		}
	}

	public static class LabelProvider implements ITableLabelProvider {

		@Override
		public void addListener(ILabelProviderListener arg0) {
		}

		@Override
		public void dispose() {
		}

		@Override
		public boolean isLabelProperty(Object arg0, String arg1) {
			return false;
		}

		@Override
		public void removeListener(ILabelProviderListener arg0) {
		}

		@Override
		public Image getColumnImage(Object element, int index) {
			return null;
		}

		@Override
		public String getColumnText(Object element, int index) {
			TraficStatistics stats = (TraficStatistics) element;

			switch (index) {
			case 0:
				return stats.isMine ? "自" : "";
			case 1:
				return stats.macAddress;
			case 2:
				return stats.playerName;
			case 3:
				return String.format("%.1f", stats.currentInKbps);
			case 4:
				return String.format("%.1f", stats.currentOutKbps);
			case 5:
				return Long.toString(stats.totalInBytes);
			case 6:
				return Long.toString(stats.totalOutBytes);
			}

			return "";
		}
	}

	public static final ViewerSorter MINE_SORTER = new ViewerSorter() {
		@Override
		public int compare(Viewer viewer, Object e1, Object e2) {
			TraficStatistics s1 = (TraficStatistics) e1;
			TraficStatistics s2 = (TraficStatistics) e2;
			if (s1.isMine == s2.isMine)
				return 0;
			return s1.isMine ? 1 : -1;
		}
	};

	public static final ViewerSorter MAC_ADDRESS_SORTER = new ViewerSorter() {
		@Override
		public int compare(Viewer viewer, Object e1, Object e2) {
			TraficStatistics s1 = (TraficStatistics) e1;
			TraficStatistics s2 = (TraficStatistics) e2;
			return s1.macAddress.compareTo(s2.macAddress);
		}
	};

	public static final ViewerSorter PLAYER_NAME_SORTER = new ViewerSorter() {
		@Override
		public int compare(Viewer viewer, Object e1, Object e2) {
			TraficStatistics s1 = (TraficStatistics) e1;
			TraficStatistics s2 = (TraficStatistics) e2;

			String pn1 = s1.playerName;
			if (pn1 == null)
				pn1 = "";
			String pn2 = s2.playerName;
			if (pn2 == null)
				pn2 = "";
			return pn1.compareTo(pn2);
		}
	};

	public static final ViewerSorter IN_SPEED_SORTER = new ViewerSorter() {
		@Override
		public int compare(Viewer viewer, Object e1, Object e2) {
			TraficStatistics s1 = (TraficStatistics) e1;
			TraficStatistics s2 = (TraficStatistics) e2;
			return Double.compare(s1.currentInKbps, s2.currentInKbps);
		}
	};

	public static final ViewerSorter OUT_SPEED_SORTER = new ViewerSorter() {
		@Override
		public int compare(Viewer viewer, Object e1, Object e2) {
			TraficStatistics s1 = (TraficStatistics) e1;
			TraficStatistics s2 = (TraficStatistics) e2;
			return Double.compare(s1.currentOutKbps, s2.currentOutKbps);
		}
	};

	public static final ViewerSorter TOTAL_IN_SORTER = new ViewerSorter() {
		@Override
		public int compare(Viewer viewer, Object e1, Object e2) {
			TraficStatistics s1 = (TraficStatistics) e1;
			TraficStatistics s2 = (TraficStatistics) e2;
			return Utility.compare(s1.totalInBytes, s2.totalInBytes);
		}
	};

	public static final ViewerSorter TOTAL_OUT_SORTER = new ViewerSorter() {
		@Override
		public int compare(Viewer viewer, Object e1, Object e2) {
			TraficStatistics s1 = (TraficStatistics) e1;
			TraficStatistics s2 = (TraficStatistics) e2;
			return Utility.compare(s1.totalOutBytes, s2.totalOutBytes);
		}
	};
}
