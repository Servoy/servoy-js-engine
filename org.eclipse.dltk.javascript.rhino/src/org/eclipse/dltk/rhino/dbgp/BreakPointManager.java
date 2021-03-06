package org.eclipse.dltk.rhino.dbgp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class BreakPointManager {

	private HashMap fileMap = new HashMap();
	private HashMap ids = new HashMap();
	private HashMap returnNames = new HashMap();
	private HashMap watchpoints = new HashMap();
	private HashMap callNames = new HashMap();

	private boolean suspendOnExit;

	private boolean suspendOnEntry;

	private boolean suspendOnException;

	/**
	 * @return the suspendOnEntry
	 */
	public synchronized boolean getSuspendOnEntry() {
		return suspendOnEntry;
	}

	/**
	 * @return the suspendOnExit
	 */
	public synchronized boolean getSuspendOnExit() {
		return suspendOnExit;
	}

	/**
	 * @return the suspendOnException
	 */
	public synchronized boolean getSuspendOnException() {
		return suspendOnException;
	}

	/**
	 * @param suspendOnException
	 *            the suspendOnException to set
	 */
	public synchronized void setSuspendOnException(boolean suspendOnException) {
		this.suspendOnException = suspendOnException;
	}

	public synchronized void setSuspendOnExit(boolean parseBoolean) {
		this.suspendOnExit = parseBoolean;
	}

	public synchronized void setSuspendOnEntry(boolean parseBoolean) {
		this.suspendOnEntry = parseBoolean;
	}

	public synchronized void removeBreakPoint(String id) {
		BreakPoint object = (BreakPoint) ids.get(id);
		if (object != null)
			removeBreakPoint(object);
	}

	public synchronized final void addBreakPoint(BreakPoint point) {
		if (point.isReturn) {
			returnNames.put(point.getFullyQualifiedName(), point);
		}
		if (point.isCall) {
			callNames.put(point.getFullyQualifiedName(), point);
		}

		if (point.isWatch) {
			ArrayList object = (ArrayList) watchpoints.get(point.expression);
			if (object == null) {
				object = new ArrayList();
				watchpoints.put(point.expression, object);
			}
			object.add(point);

		}
		HashMap object = (HashMap) fileMap.get(point.file);
		if (object == null) {
			object = new HashMap();
			fileMap.put(point.file, object);
		}
		object.put(new Integer(point.line), point);
		ids.put("p" + point.id, point);
	}

	public synchronized void removeBreakPoint(BreakPoint point) {
		if (point.isReturn) {
			returnNames.remove(point.getFullyQualifiedName());
		}
		if (point.isCall) {
			callNames.remove(point.getFullyQualifiedName());
		}
		if (point.isWatch) {
			watchpoints.remove(point.expression);
		}
		HashMap object = (HashMap) fileMap.get(point.file);
		if (object == null) {
			return;
		}
		object.remove(new Integer(point.line));
		ids.remove("p" + point.id);
	}

	public synchronized BreakPoint hit(String sourcePath, int lineNumber) {
		HashMap q = (HashMap) fileMap.get(sourcePath);

		if (q == null)
			return null;
		Integer lnNumber = new Integer(lineNumber);
		BreakPoint point = (BreakPoint) q.get(lnNumber);

		if (point == null)
			return null;
		point.currentHitCount++;
		if (point.hitValue > 0) {

			if (point.hitCondition == 1) {
				if (point.hitValue >= point.currentHitCount)
					return null;
			}
			if (point.hitCondition == 2) {
				if (point.hitValue != point.currentHitCount)
					return null;
			}
			if (point.hitCondition == 3) {
				if (point.currentHitCount % point.hitValue != 0)
					return null;
			}
		}
		if (point.isTemporary)
			q.remove(lnNumber);
		return point;
	}

	public synchronized void updateBreakpoint(String id, String newState,
			String newLine, String hitValue, String hitCondition,
			String condexpression) {
		BreakPoint p = (BreakPoint) ids.get(id);
		if (p != null) {
			if (newState != null) {
				newState = newState.trim();

				if (newState.equals("enabled")) {

					p.setEnabled(true);
				} else if (newState.equals("disabled")) {

					p.setEnabled(false);
				}
			}
			if (newLine != null) {
				HashMap map = (HashMap) fileMap.get(p.file);
				Integer nl = new Integer(p.line);
				BreakPoint po = (BreakPoint) map.get(nl);
				if (po != p) {
					System.out.println("Error");
				} else {
					map.remove(nl);
					p.line = Integer.parseInt(newLine);
					map.put(new Integer(p.line), p);
				}
			}
			if (hitValue != null) {
				p.hitValue = Integer.parseInt(hitValue);
			}
			if (hitCondition != null) {
				p.setHitCondition(hitCondition);
			}
			if (!p.isWatch) {
				p.expression = condexpression;
			} else {
				p.isModification = condexpression.charAt(condexpression
						.length() - 1) == '1';
				p.isAccess = condexpression.charAt(condexpression.length() - 2) == '1';
				p.expression = condexpression.substring(0,
						condexpression.length() - 2);
			}

		}
	}

	public synchronized BreakPoint hitEnter(String sn) {
		return (BreakPoint) callNames.get(sn);
	}

	public synchronized BreakPoint hitExit(String sn) {
		return (BreakPoint) returnNames.get(sn);
	}

	public synchronized List getWatchPoints(String property) {
		return (List) watchpoints.get(property);
	}

	public synchronized BreakPoint getBreakpoint(String id) {
		return (BreakPoint) ids.get(id);
	}

	/**
	 * 
	 */
	public synchronized void removeBreakPoints() {
		fileMap = new HashMap();
		ids = new HashMap();
		returnNames = new HashMap();
		watchpoints = new HashMap();
		callNames = new HashMap();
		suspendOnEntry = false;
		suspendOnException = false;
		suspendOnExit = false;
	}
}
