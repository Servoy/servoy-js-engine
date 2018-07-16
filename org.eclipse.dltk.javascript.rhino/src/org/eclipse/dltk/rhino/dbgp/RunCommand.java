/**
 * 
 */
package org.eclipse.dltk.rhino.dbgp;

import java.util.HashMap;

final class RunCommand extends DBGPDebugger.Command {
	/**
	 * 
	 */
	private final DBGPDebugger debugger;

	/**
	 * @param debugger
	 */
	RunCommand(DBGPDebugger debugger) {
		this.debugger = debugger;
	}

	void parseAndExecute(String command, HashMap options) {
		String object = (String) options.get("-i");
		this.debugger.setTransactionId(object);
		while (!this.debugger.isInited) {
			Thread.yield();
		}
		synchronized (this.debugger) {
			this.debugger.notify();
		}
		DBGPStackManager stackManager = this.debugger.getStackManager();
		if (stackManager != null) stackManager.resume();
		// printResponse("<response command=\"run\"\r\n"
		// + "status=\"starting\"" + " reason=\"ok\""
		// + " transaction_id=\"" + object + "\">\r\n"
		// + "</response>\r\n" + "");
	}
}