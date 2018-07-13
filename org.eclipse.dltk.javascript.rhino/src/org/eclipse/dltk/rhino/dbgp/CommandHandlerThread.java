package org.eclipse.dltk.rhino.dbgp;

public class CommandHandlerThread extends Thread
{
	public CommandHandlerThread(Runnable run) {
		super(run, "Debug Command Handler");
	}
}
