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
		if (this.debugger.getStackManager().getStackDepth() > 0) {
			this.debugger.getStackManager().stepOver();
		} else {
			synchronized (this.debugger) {
				while (!this.debugger.isInited) {
					Thread.yield();
				}
				this.debugger.notify();
			}
			this.debugger.getStackManager().resume();
		}
	}
}