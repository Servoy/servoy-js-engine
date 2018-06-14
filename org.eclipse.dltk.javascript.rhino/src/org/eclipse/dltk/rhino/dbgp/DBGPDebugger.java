package org.eclipse.dltk.rhino.dbgp;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeJavaArray;
import org.mozilla.javascript.NativeJavaClass;
import org.mozilla.javascript.NativeJavaMethod;
import org.mozilla.javascript.NativeJavaObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.UniqueTag;
import org.mozilla.javascript.Wrapper;
import org.mozilla.javascript.debug.DebugFrame;
import org.mozilla.javascript.debug.DebuggableScript;
import org.mozilla.javascript.debug.Debugger;
import org.mozilla.javascript.debug.IDebuggerWithWatchPoints;
import org.mozilla.javascript.xml.XMLObject;

public class DBGPDebugger extends Thread implements Debugger,
		IDebuggerWithWatchPoints {
	/**
	 * @author jcompagner
	 */
	public interface ITerminationListener {
		public void debuggerTerminated();
	}

	static abstract class Command {
		abstract void parseAndExecute(String command, HashMap options);
	}
	
	int max_depth_feature = 2;

	Socket socket;

	private PrintStream out;

	private HashMap strategies = new HashMap();

	HashMap properties = new HashMap();

	private String runTransctionId;

	public volatile boolean isInited;

	
	private ArrayList terminationListeners;

	private final BreakPointManager breakPointManager;

	private DBGPStackManager stackmanager;
	
	private final ExecutorService executorService = Executors.newCachedThreadPool();

	public DBGPDebugger(Socket socket, String file, String string, Context ct)
			throws IOException {
		super("Debug command reader");
		this.socket = socket;
		this.breakPointManager = new BreakPointManager();
		DBGPStackManager stackmanager = DBGPStackManager.getManager(ct, this);
		setStackManager(stackmanager);
		stackmanager.suspend();
		out = new PrintStream(socket.getOutputStream());
		String response = "<init appid=\"APPID\"\r\n" + "      idekey=\""
				+ string + "\"\r\n" + "      session=\"" + string + "\"\r\n"
				+ "      thread=\"THREAD_ID\"\r\n"
				+ "      parent=\"PARENT_APPID\"\r\n"
				+ "      language=\"javascript\"\r\n"
				+ "      protocol_version=\"1.0\"\r\n"
				+ "      fileuri=\"file://" + file + "\"\r\n" + "/>";
		printResponse(response);
		strategies.put("feature_get", new FeatureGetCommand(this));
		strategies.put("feature_set", new FeatureSetCommand(this));
		strategies.put("stdin", new StdInCommand(this));
		strategies.put("stdout", new StdOutCommand(this));
		strategies.put("stderr", new StdErrCommand(this));
		strategies.put("run", new RunCommand(this));
		strategies.put("context_names", new ContextNamesCommand(this));
		strategies.put("stop", new StopCommand(this));
		strategies.put("step_over", new StepOverCommand(this));
		strategies.put("step_into", new StepIntoCommand(this));
		strategies.put("step_out", new StepOutCommand(this));
		strategies.put("breakpoint_get", new GetBreakPointCommand(this));
		strategies.put("breakpoint_set", new SetBreakPointCommand(this));
		strategies.put("breakpoint_remove", new RemoveBreakPointCommand(this));
		strategies.put("breakpoint_update", new UpdateBreakPointCommand(this));
		strategies.put("context_get", new ContextGetCommand(this));
		strategies.put("property_set", new PropertySetCommand(this));
		strategies.put("eval", new EvalCommand(this));
		strategies.put("property_get", new PropertyGetCommand(this));
		strategies.put("break", new BreakCommand(this));
		strategies.put("stack_depth", new StackDepthCommand(this));
		strategies.put("stack_get", new StackGetCommand(this));
		out.flush();
	}
	
	public void setMaxDepth(int maxDepth){
		max_depth_feature = maxDepth;
	}
	
	public int getMaxDepth(){
		return max_depth_feature;
	}

	public void setContext(Context cx) {
		setStackManager(DBGPStackManager.getManager(cx, this));
	}

	/**
	 * @return
	 */
	public final BreakPointManager getBreakPointManager() {
		return breakPointManager;
	}

	public DBGPStackManager getStackManager() {
		return stackmanager;
	}

	public void setStackManager(DBGPStackManager manager) {
		this.stackmanager = manager;
	}

	synchronized void printResponse(String response) {
		if (out == null || socket == null) {
			System.err
					.println("wanted to print response to the eclipse debugger, but already closed:"
							+ response);
			return;
		}

		try {
			byte[] bytes = response.getBytes("UTF-8");
			out.print(bytes.length);
			out.write(0);
			out.write(bytes, 0, bytes.length);
			out.write(0);
			out.flush();
			if (out.checkError()) {
				try {
					if (socket != null) {
						socket.close();
						socket = null;
						out = null;
					}
				} catch (IOException ex) {
					ex.printStackTrace();
				}
			}
		} catch (UnsupportedEncodingException ex) {
			ex.printStackTrace();
		}
	}

	public boolean isConnected() {
		outputStdOut("");
		if (socket == null || socket.isClosed() || out == null) {
			return false;
		}
		return true;
	}

	public void addTerminationListener(ITerminationListener listener) {
		if (terminationListeners == null) {
			terminationListeners = new ArrayList();
		}
		terminationListeners.add(listener);
	}

	/**
	 * @param remoteDebugScriptEngine
	 */
	public void removeTerminationListener(ITerminationListener listener) {
		if (terminationListeners != null) {
			terminationListeners.remove(listener);
		}
	}
	
	protected void printProperty(String id, String fullName, Object value,
			StringBuffer properties, int level, boolean addChilds) {
		boolean hasChilds = false;

		int numC = 0;
		String vlEncoded;
		String name_of_object_class = "";
		String data_type = getDataType(value);

		if (value instanceof Scriptable) {
			hasChilds = true;
			StringBuffer stringBuffer = new StringBuffer();
			Scriptable p = (Scriptable) value;
			value = stringBuffer;
			String nv = p.getClassName();
			name_of_object_class = nv;
			if (p instanceof NativeJavaObject) {

				NativeJavaObject obj = (NativeJavaObject) p;

				Object unwrap = obj.unwrap();
				if (unwrap instanceof Class) {
					nv = ((Class) unwrap).getName();
				} else if (unwrap.getClass().isArray()) {
					nv = "Array";
					// String string = unwrap.getClass().getName();
					// int len = Array.getLength(unwrap);
					// int q = string.indexOf('[');
					// if (q != -1)
					// string = string.substring(0, q);
					// int q1 = string.indexOf(']');
					// nv = string + "[" + len + "]";
					// if (q1 != -1)
					// nv += string.substring(q1);
				} else {
					if (unwrap instanceof String) {
						nv = "JavaString " + '"' + unwrap.toString() + '"';
					} else {
						String string = unwrap.toString();

						nv = string;
						// nv = unwrap.getClass().getName() + "(" + string +
						// ")";
					}
				}

			} else if (p instanceof Wrapper) {
				Wrapper wrapper = (Wrapper) p;
				Object wrapped = wrapper.unwrap();

				if (wrapped == null) {
					nv = "Undefined";
				} else if (!wrapped.getClass().isArray()) {
					// if it is an array let the normal array handling do the
					// job
					// now just do toString();
					nv = wrapped.toString();
				}
			} else if (p instanceof XMLObject) {
				nv = ((XMLObject) p).toString();
				data_type = "XML";
			}

			stringBuffer.append(Base64Helper.encodeString(nv));
			if (addChilds) {
				HashSet duplicates = new HashSet();
				Scriptable prototype = p;
				boolean includeFunc = true;
				while (prototype != null) {
					numC += createChilds(fullName, level, stringBuffer,
							prototype, duplicates, includeFunc);
					includeFunc = false;
					prototype = prototype.getPrototype();
				}
			} else {
				HashSet duplicates = new HashSet();
				Scriptable prototype = p;
				boolean includeFunc = true;
				while (prototype != null) {
					Object[] ids = null;
					if (prototype instanceof LazyInitScope) {
						ids = ((LazyInitScope) prototype).getInitializedIds();
					} else {
						ids = prototype.getIds();
					}
					for (int a = 0; a < ids.length; a++) {
						if (!duplicates.add(ids[a]))
							continue;
						Object pvalue = null;
						try {
							if (ids[a] instanceof Integer) {
								pvalue = prototype
										.get(((Integer) ids[a]).intValue(), p);
							} else
								pvalue = prototype.get(ids[a].toString(), p);
						} catch (Exception e) {
							// dont let the debugger crash.
							e.printStackTrace();
						}

						if (addProperty(pvalue) && (includeFunc || !(pvalue instanceof Function))) // HACK because
						// ShowFunctionsAction
						// doesnt work because of
						// the lazy behavior of
						// plugins in Eclipse
						{
							numC++;
						}
					}
					includeFunc = false;
					prototype = prototype.getPrototype();
				}
			}
			vlEncoded = stringBuffer.toString();
		} else {
			if (!(value instanceof Undefined)) {
				if (value == UniqueTag.NOT_FOUND) {
					vlEncoded = "";
				} else
					vlEncoded = Base64Helper.encodeString(value != null ? value
							.toString() : "null");
			} else {
				vlEncoded = Base64Helper.encodeString("Undefined");
			}
			if (value != null)
				name_of_object_class = value.getClass().getName();
		}
		id = escapeHTML(id);
		fullName = escapeHTML(fullName);

		properties.append("<property\r\n" + "    name=\"" + id + "\"\r\n"
				+ "    fullname=\"" + fullName + "\"\r\n" + "    type=\""
				+ data_type + "\"\r\n" + "    classname=\""
				+ name_of_object_class + "\"\r\n" + "    constant=\"0\"\r\n"
				+ "    children=\"" + (hasChilds ? 1 : 0) + "\"\r\n"
				+ "    encoding=\"base64\"\r\n" + "    numchildren=\"" + numC
				+ "\">\r\n" + vlEncoded + "</property>\r\n");
	}

	private static String escapeHTML(String content) {
		content = replace(content, '&', "&amp;");
		content = replace(content, '"', "&quot;");
		content = replace(content, '<', "&lt;");
		return replace(content, '>', "&gt;");
	}

	private static String replace(String text, char c, String s) {

		int previous = 0;
		int current = text.indexOf(c, previous);

		if (current == -1)
			return text;

		StringBuffer buffer = new StringBuffer();
		while (current > -1) {
			buffer.append(text.substring(previous, current));
			buffer.append(s);
			previous = current + 1;
			current = text.indexOf(c, previous);
		}
		buffer.append(text.substring(previous));

		return buffer.toString();
	}

	/**
	 * @param fullName
	 * @param level
	 * @param stringBuffer
	 * @param p
	 * @param ids
	 */
	private int createChilds(String fullName, int level,
			StringBuffer stringBuffer, Scriptable p, HashSet duplicates, boolean includeFunc) {
		Object[] ids = null;
		if (p instanceof LazyInitScope) {
			ids = ((LazyInitScope) p).getInitializedIds();
		} else if (p instanceof ScriptableObject && !(p instanceof XMLObject)
				&& !(p instanceof NativeArray)) {

			ids = ((ScriptableObject) p).getAllIds();
		} else {
			ids = p.getIds();
		}
		int counter = 0;
		for (int a = 0; a < ids.length; a++) {
			if (!duplicates.add(ids[a]))
				continue;
			Object pvalue = null;
			try {
				if (ids[a] instanceof Integer) {
					pvalue = p.get(((Integer) ids[a]).intValue(), p);
				} else
					pvalue = p.get(ids[a].toString(), p);
			} catch (Throwable e) {
				// dont let the debugger crash.
				e.printStackTrace();
			}
			if (addProperty(pvalue) && (includeFunc || !(pvalue instanceof Function))) 
			{
				counter++;
				if (ids[a] instanceof Integer) {
					printProperty(ids[a].toString(), fullName + "[" + ids[a]
							+ "]", pvalue, stringBuffer, level + 1, level+1 < getMaxDepth());
				} else {
					printProperty(ids[a].toString(), fullName + "." + ids[a],
							pvalue, stringBuffer, level + 1, level+1 < getMaxDepth());
				}
			}
			if (counter > 5000)
				break;
		}
		return counter;
	}
	
	/* // HACK because ShowFunctionsAction doesnt work because of the lazy behavior of plugins in Eclipse
	 * this method skips NativeJavaMethods and Servoys own methods, the rest are just reported */
	public static boolean addProperty(Object property) {
		if (property instanceof NativeJavaMethod)
			return false;
		if (property instanceof Function) {
			Function function = (Function) property;
			Object methodNameObj = function.get("_methodname_", function);
			return (methodNameObj == null || methodNameObj
					.equals(Scriptable.NOT_FOUND)) ? true : false;
		}
		return true;
	}

	private String getDataType(Object value) {
		String data_type = "Object";
		if (value instanceof Function) {
			data_type = "function";
		} else if (value instanceof NativeJavaArray) {
			data_type = "javaarray";
		} else if (value instanceof NativeArray) {
			data_type = "array";
		} else if (value instanceof NativeJavaObject) {
			data_type = "javaobject";
		} else if (value instanceof NativeJavaClass) {
			data_type = "javaclass";
		} else if (value instanceof String) {
			data_type = "string";
		} else if (value instanceof Number) {
			data_type = "number";
		} else if (value instanceof Boolean) {
			data_type = "boolean";
		} else if (value instanceof Date) {
			data_type = "date";
		} else if (value instanceof Undefined || value == null) {
			data_type = "undefined";
		} else if (value instanceof Wrapper) {
			return getDataType(((Wrapper) value).unwrap());
		}
		return data_type;
	}

	public void run() {
		try {
			DataInputStream ds = new DataInputStream(socket.getInputStream());
			StringBuffer buf = new StringBuffer();
			while (ds.available() >= 0) {
				int c = ds.read();
				if (c < 0)
					break;
				if (c < 32) {
					final String command = buf.toString();
					executorService.execute(new Runnable() {
						@Override
						public void run() {
							executeCommand(command);
						}
					});

					buf = new StringBuffer();
				} else {
					buf.append((char) c);
				}

			}
		} catch (Exception e) {
			// e.printStackTrace(); // ignore just a disconnect exception
			try {
				if (socket != null)
					socket.close();
			} catch (IOException ex) {
			}
			close();
		}

	}
	
	private void executeCommand(final String command) {
		int indexOf = command.indexOf(' ');
		if (indexOf != -1)

		{
			String commandId = command.substring(0, indexOf);
			Command object = (Command) strategies.get(commandId);
			if (object == null) {
				System.err.println(commandId);
				return;
			}
			HashMap options = new HashMap();

			String result = command.substring(indexOf);
			int index = result.indexOf(" -");
			while (index != -1 && index != result.length()) {
				int space = result.indexOf(' ', index + 2);
				int nextIndex = result.indexOf(" -", space + 1);
				if (nextIndex == -1)
					nextIndex = result.length();
				String key = result.substring(index + 1, space);
				String value = result.substring(space + 1,
						nextIndex);

				options.put(key, value);
				index = nextIndex;

			}
			if (!isInited && object instanceof RunCommand) {
				synchronized (this) {
					isInited = true;
					notifyAll();
				}
			}
			try {
				Context.enter();
				object.parseAndExecute(result, options);
			} catch (Throwable t) {
				t.printStackTrace();
			} finally {
				Context.exit();
			}
		}
	}

	public DebugFrame getFrame(Context cx, DebuggableScript fnOrScript) {
		return new DBGPDebugFrame(cx, fnOrScript, this);
	}

	public void handleCompilationDone(Context cx, DebuggableScript fnOrScript,
			String source) {
	}

	public synchronized boolean sendBreak(String reason) {
		printResponse("<response command=\"run\"\r\n" + "status=\"break\""
				+ " reason=\"ok\"" + " transaction_id=\"" + runTransctionId
				+ "\">\r\n" + Base64Helper.encodeString(reason)
				+ "</response>\r\n" + "");
		// this transaction id is now send, set it to nul;
		runTransctionId = null;
		return socket != null && out != null;
	}

	public void outputStdOut(String value) {
		printResponse("<stream type=\"stdout\">\r\n"
				+ Base64Helper.encodeString(value) + "</stream>\r\n" + "");
	}

	public void outputStdErr(String value) {
		printResponse("<stream type=\"stderr\">\r\n"
				+ Base64Helper.encodeString(value) + "</stream>\r\n" + "");
	}

	public void notifyEnd() {
		sendEnd(true);
		System.exit(0);
	}

	public void sendEnd(boolean close) {
		printResponse("<response command=\"run\"\r\n" + "status=\"stopped\""
				+ " reason=\"ok\"" + " transaction_id=\"" + runTransctionId
				+ "\">\r\n" + "</response>\r\n" + "");
		if (close) close();
	}

	public void close() {
		isInited = false;
		runTransctionId = null;
		getBreakPointManager().removeBreakPoints();
		// shouldnt all the stackmanagers be resumed?
		DBGPStackManager.stopAll();
		if (socket != null) {
			try {
				socket.close();
				socket = null;
				out = null;
			} catch (IOException ex) {
				ex.printStackTrace();
			}
		}
		if (terminationListeners != null) {
			for (int i = 0; i < terminationListeners.size(); i++) {
				ITerminationListener listener = (ITerminationListener) terminationListeners
						.get(i);
				listener.debuggerTerminated();
			}
		}
	}

	public synchronized void setTransactionId(String id) {
		if (runTransctionId != null) {
			printResponse("<response command=\"run\"\r\n" + "status=\"running\""
					+ " reason=\"ok\"" + " transaction_id=\"" + runTransctionId
					+ "\"></response>\r\n" );
		}
		runTransctionId = id;
	}

	public void access(String property, Scriptable object) {
		ArrayList list = (ArrayList) getBreakPointManager().getWatchPoints(
				property);
		if (list != null) {
			int size = list.size();

			for (int a = 0; a < size; a++) {
				BreakPoint watchPoint = (BreakPoint) list.get(a);
				if (watchPoint != null) {
					if (watchPoint.enabled)
						if (watchPoint.isAccess) {
							String wkey = watchPoint.file + watchPoint.line;
							String s = (String) cache.get(object);
							if ((s != null) && (s.equals(wkey))) {
								getStackManager().sendSuspend(
										"Break on access watchpoint: "
												+ property);
							}
						}
				}
			}
		}
	}

	WeakHashMap cache = new WeakHashMap();

	public void modification(String property, Scriptable object) {

		ArrayList list = (ArrayList) getBreakPointManager().getWatchPoints(
				property);
		if (list != null && getStackManager().getStackDepth() > 0) {
			int size = list.size();
			for (int a = 0; a < size; a++) {

				BreakPoint watchPoint = (BreakPoint) list.get(a);
				if (watchPoint != null) {
					if (watchPoint.enabled) {
						String sn = getStackManager().getStackFrame(0)
								.getSourceName();
						int ln = getStackManager().getStackFrame(0)
								.getLineNumber();
						String key = sn + ln;
						String wkey = watchPoint.file + watchPoint.line;
						if (key.equals(wkey)) {
							cache.put(object, wkey);
						}
						if (watchPoint.isModification) {
							Object object2 = cache.get(object);
							if (object2 != null)
								if (object2.equals(wkey)) {
									getStackManager().sendSuspend(
											"Break on modification watchpoint: "
													+ property);
								}
						}
					}
				}
			}
		}
	}

	public void setProperty(String name, String value) {

	}
}
