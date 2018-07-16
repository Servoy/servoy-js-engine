/**
 * 
 */
package org.eclipse.dltk.rhino.dbgp;

import java.util.HashMap;

final class PropertySetCommand extends DBGPDebugger.Command {
	/**
	 * 
	 */
	private final DBGPDebugger debugger;

	/**
	 * @param debugger
	 */
	PropertySetCommand(DBGPDebugger debugger) {
		this.debugger = debugger;
	}

	void parseAndExecute(String command, HashMap options) {
		String name = ((String) options.get("-n"));
		int num = Integer.parseInt((String) options.get("-d"));
		String value = Base64Helper.decodeString((String) options.get("--"));
		if (num >= 0) {
			DBGPDebugFrame fr = this.debugger.getStackManager().getStackFrame(
					num);
			fr.setValue(name, value);
		} else {
			if (name.equals("suspendOnException")) {
				boolean parseBoolean = new Boolean(value).booleanValue();
				this.debugger.getBreakPointManager()
						.setSuspendOnException(parseBoolean);
			} else if (name.equals("suspendOnEntry")) {
				boolean parseBoolean = new Boolean(value).booleanValue();
				this.debugger.getBreakPointManager()
						.setSuspendOnEntry(parseBoolean);
			} else if (name.equals("suspendOnExit")) {
				boolean parseBoolean = new Boolean(value).booleanValue();
				this.debugger.getBreakPointManager()
						.setSuspendOnExit(parseBoolean);
			} else
				debugger.setProperty(name, value);
		}
		this.debugger.printResponse("<response command=\"property_set\"\r\n"
				+ " transaction_id=\"" + options.get("-i")
				+ "\" success=\"1\" " + ">\r\n" + "</response>\r\n" + "");
	}
}