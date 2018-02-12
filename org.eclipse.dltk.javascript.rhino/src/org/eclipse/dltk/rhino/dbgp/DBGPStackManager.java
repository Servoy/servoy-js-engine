package org.eclipse.dltk.rhino.dbgp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Observer;
import java.util.WeakHashMap;
import java.util.Map.Entry;

import org.mozilla.javascript.Context;

public class DBGPStackManager {

	protected static WeakHashMap map = new WeakHashMap();

	private ArrayList stack = new ArrayList();

	private boolean needSuspend;

	private final DBGPDebugger observer;

	private boolean suspenOnChangeLine;

	private boolean stop;
	
	private boolean steppedOut;

	public static DBGPStackManager getManager(Context cx, DBGPDebugger debugger) {
		DBGPStackManager object = (DBGPStackManager) map.get(cx);
		if (object != null)
			return object;
		object = new DBGPStackManager(debugger);
		map.put(cx, object);
		return object;
	}

	public static DBGPStackManager removeManager(Context cx) {
		return (DBGPStackManager) map.remove(cx);
	}

	private DBGPStackManager(DBGPDebugger debugger) {
		observer = debugger;
	}

	public final BreakPointManager getManager() {
		return observer.getBreakPointManager();
	}

	public void enter(DBGPDebugFrame debugFrame) {
		stack.add(debugFrame);
		String sn = debugFrame.getWhere();

		if (sn != null) {
			BreakPoint hit = getManager().hitEnter(sn);
			if (hit != null && checkBreakpoint(debugFrame, hit))
				suspenOnChangeLine = true;
		}

		if (!suspenOnChangeLine && (getManager().getSuspendOnEntry()) || steppedOut) {
			if (debugFrame.getWhere().endsWith("module")) {
				sendSuspend(null);
			} else
				suspenOnChangeLine = true;
		}
	}

	public void exit(DBGPDebugFrame debugFrame) {
		if (needSuspend || getManager().getSuspendOnExit()) {

			sendSuspend("Break on exit");
		}
		String sn = debugFrame.getWhere();

		if (sn != null) {
			BreakPoint hit = getManager().hitExit(sn);
			if (hit != null && checkBreakpoint(debugFrame, hit))
				sendSuspend("Break on exit breakpoint: " + hit.method);
		}
		if (debugFrame.isSuspend() && stack.size() > 1) {
			steppedOut = true;
		}
		stack.remove(debugFrame);

	}

	public void changeLine(DBGPDebugFrame frame, int lineNumber) {
		if (stop) {
			System.err.print("Current script terminated");
			if (throwException) {
				throwException = false;
				throw new RuntimeException("Script execution stopped");
			}
			return;
		}
		boolean skipOneSuspend = false;
		if (suspenOnChangeLine) {
			skipOneSuspend = true;
			steppedOut = false;
			suspenOnChangeLine = false;
			sendSuspend(null);
		}
		if (frame.isSuspend()) {
			needSuspend = true;
		}
		BreakPoint hit = getManager().hit(frame.getSourceName(), lineNumber);
		if (!skipOneSuspend && checkBreakpoint(frame, hit)) {
			sendSuspend("Break on line breakpoint " + lineNumber);
		}
	}

	private boolean checkBreakpoint(DBGPDebugFrame frame, BreakPoint hit) {

		if (hit != null) {
			if (hit.isEnabled()) {
				if (hit.expression != null) {
					Object eval = frame.eval(hit.expression);
					if (eval != null) {
						if (eval.equals(Boolean.TRUE)) {
							needSuspend = true;
						} else
							needSuspend = false;
					} else
						needSuspend = false;
				} else
					needSuspend = true;
			}
		}
		return needSuspend;
	}

	public void exceptionThrown(Throwable ex) {

		if (getManager().getSuspendOnException()) {
			String reason = null;
			while (ex.getCause() != null) {
				ex = ex.getCause();
			}
			reason = "Break on Exception: " + ex.getLocalizedMessage();
			sendSuspend(reason);
		}

	}

	private boolean suspended = false;

	private boolean throwException;

	/**
	 * @param reason
	 *            TODO
	 * 
	 */
	public void sendSuspend(String reason) {
		if (stop)
			return;
		throwException = false;
		steppedOut = false;
		if (observer.sendBreak(reason)) {
			synchronized (this) {
				suspended = true;
				while (suspended) {
					try {
						this.wait(5000);
						if (!observer.isConnected()) {
							suspended = false;
							observer.close();
						}
						if (throwException) {
							throwException = false;
							throw new RuntimeException(
									"Script execution stopped");
						}
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
		}
	}

	public void suspend() {
		needSuspend = true;
	}

	public int getStackDepth() {
		return stack.size();
	}

	public DBGPDebugFrame getStackFrame(int parseInt) {
		int stackCounter = stack.size() - parseInt - 1;
		if (stackCounter >= 0) {
			return (DBGPDebugFrame) stack.get(stackCounter);
		}
		return null;
	}

	public int getLineNumber(String level) {
		return getStackFrame(0).getLineNumber();
	}

	public void registerBreakPoint(BreakPoint p) {
		getManager().addBreakPoint(p);
	}

	public synchronized void resume() {
		for (int a = 0; a < this.getStackDepth(); a++) {
			this.getStackFrame(a).setSuspend(false);
		}
		endSuspend();
	}

	public synchronized void resumeWithStop() {
		for (int a = 0; a < this.getStackDepth(); a++) {
			this.getStackFrame(a).setSuspend(false);
		}
		throwException = true;
		endSuspend();
	}

	/**
	 * 
	 */
	private void endSuspend() {
		suspended = false;
		this.needSuspend = false;
		this.notifyAll();
	}

	public synchronized void stepOver() {
		getStackFrame(0).setSuspend(true);
		if (this.getStackDepth() > 1) {
			getStackFrame(1).setSuspend(true);
		}
		endSuspend();
	}

	public synchronized void stepIn() {
		this.needSuspend = true;
		suspended = false;
		this.notifyAll();
	}

	public boolean isSuspended() {
		return suspended;
	}

	public synchronized void stepOut() {
		getStackFrame(0).setSuspend(false);
		if (this.getStackDepth() > 1) {
			getStackFrame(1).setSuspend(true);
		}
		endSuspend();
		steppedOut = true;
	}

	public void removeBreakpoint(String id) {
		this.getManager().removeBreakPoint(id);
	}

	public void updateBreakpoint(String id, String newState, String newLine,
			String hitValue, String hitCondition, String condExpr) {
		this.getManager().updateBreakpoint(id, newState, newLine, hitValue,
				hitCondition, condExpr);
	}

	public DBGPDebugger getDBGPDebugger() {
		return observer;
	}

	public BreakPoint getBreakpoint(String id) {
		return this.getManager().getBreakpoint(id);
	}

	public static void stopAll() {
		Iterator iterator = map.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry entry = (Entry) iterator.next();
			((DBGPStackManager) entry.getValue()).stop();
			iterator.remove();
		}

	}

	/**
	 * 
	 */
	public void stop() {
		stop = true;
		suspenOnChangeLine = false;
		resumeWithStop();
	}

}
