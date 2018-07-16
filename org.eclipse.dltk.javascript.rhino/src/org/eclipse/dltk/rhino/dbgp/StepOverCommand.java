/**
 * 
 */
package org.eclipse.dltk.rhino.dbgp;

import java.util.HashMap;

final class StepOverCommand extends DBGPDebugger.Command {
	/**
	 * 
	 */
	private final DBGPDebugger debugger;

	/**
	 * @param debugger
	 */
	StepOverCommand(DBGPDebugger debugger) {
		this.debugger = debugger;
	}

	void parseAndExecute(String command, HashMap options) {
		Object tid = options.get("-i");
		this.debugger.setTransactionId((String) tid);
		DBGPStackManager stackManager = this.debugger.getStackManager();
		if (stackManager != null && stackManager.getStackDepth() > 0) {
			stackManager.stepOver();
		} else {
			synchronized (this.debugger) {
				while (!this.debugger.isInited) {
					Thread.yield();
				}
				this.debugger.notify();
			}
			stackManager = this.debugger.getStackManager();
			if (stackManager != null) stackManager.resume();
		}
	}
}