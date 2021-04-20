/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.lang.reflect.*;
import java.io.*;

/**
 * Wrappper class for Method and Constructor instances to cache
 * getParameterTypes() results, recover from IllegalAccessException
 * in some cases and provide serialization support.
 *
 * @author Igor Bukanov
 */

public final class MemberBox implements Serializable
{
	private static Method canAccess = null;
	
	static {
		try {
			canAccess = Method.class.getMethod("canAccess", Object.class);
			System.err.println(canAccess);
		} catch (Exception e) {
			// ignore
		}
	}
    static final long serialVersionUID = 6358550398665688245L;

    private transient Member memberObject;
    transient Class<?>[] argTypes;
    transient Object delegateTo;
    transient boolean vararg;
    transient Class<?> returnType;

    public MemberBox(Method method)
    {
        init(method);
    }

    MemberBox(Constructor<?> constructor)
    {
        init(constructor);
    }

    private void init(Method method)
    {
        this.memberObject = method;
        this.argTypes = method.getParameterTypes();
        this.vararg = VMBridge.instance.isVarArgs(method);
        this.returnType = method.getReturnType();
    }

    private void init(Constructor<?> constructor)
    {
        this.memberObject = constructor;
        this.argTypes = constructor.getParameterTypes();
        this.vararg = VMBridge.instance.isVarArgs(constructor);
    }

	public Class<?>[] getParameterTypes() {
		return argTypes;
	}

	/**
	 * @return the returnType
	 */
	public Class<?> getReturnType() {
		return returnType;
	}
	
    public Method method()
    {
        return (Method)memberObject;
    }

    Constructor<?> ctor()
    {
        return (Constructor<?>)memberObject;
    }

    Member member()
    {
        return memberObject;
    }

    public boolean isMethod()
    {
        return memberObject instanceof Method;
    }

    boolean isCtor()
    {
        return memberObject instanceof Constructor;
    }

    boolean isStatic()
    {
        return Modifier.isStatic(memberObject.getModifiers());
    }

    String getName()
    {
        return memberObject.getName();
    }

    public Class<?> getDeclaringClass()
    {
        return memberObject.getDeclaringClass();
    }

    String toJavaDeclaration()
    {
        StringBuilder sb = new StringBuilder();
        if (isMethod()) {
            Method method = method();
            sb.append(method.getReturnType());
            sb.append(' ');
            sb.append(method.getName());
        } else {
            Constructor<?> ctor = ctor();
            String name = ctor.getDeclaringClass().getName();
            int lastDot = name.lastIndexOf('.');
            if (lastDot >= 0) {
                name = name.substring(lastDot + 1);
            }
            sb.append(name);
        }
        sb.append(JavaMembers.liveConnectSignature(argTypes));
        return sb.toString();
    }

    @Override
    public String toString()
    {
        return memberObject.toString();
    }

    Object invoke(Object target, Object[] args)
    {
        Method method = method();
        try {
            try {
				if (!canAccess(target, method)) {
					Class<?> declaredClz = method.getDeclaringClass();
					Method met = null;
					outer: do {
						for (Class<?> interfaceClass : declaredClz.getInterfaces()) {
							met = getAccessibleMethod(target, interfaceClass, getName(), method.getParameterTypes());
							if (met != null) break outer;
						}
						declaredClz = declaredClz.getSuperclass();
						if (declaredClz != null) {
							met = getAccessibleMethod(target, declaredClz, getName(), method.getParameterTypes());
							if (met != null) break outer;
						}
					} while (met == null && declaredClz != null);
					if (met != null) {
						memberObject = met;
						method = met;
					}
				}
                return method.invoke(target, args);
            } catch (IllegalAccessException ex) {
                Method accessible = searchAccessibleMethod(method, argTypes);
                if (accessible != null) {
                    memberObject = accessible;
                    method = accessible;
                } else {
                    if (!VMBridge.instance.tryToMakeAccessible(method)) {
                        throw Context.throwAsScriptRuntimeEx(ex);
                    }
                }
                // Retry after recovery
                return method.invoke(target, args);
            }
        } catch (InvocationTargetException ite) {
            // Must allow ContinuationPending exceptions to propagate unhindered
            Throwable e = ite;
            do {
                e = ((InvocationTargetException) e).getTargetException();
            } while ((e instanceof InvocationTargetException));
            if (e instanceof ContinuationPending)
                throw (ContinuationPending) e;
            throw Context.throwAsScriptRuntimeEx(e);
        } catch (Exception ex) {
            throw Context.throwAsScriptRuntimeEx(ex);
        }
    }

	/**
	 * @param target
	 * @param method
	 * @return
	 */
	private static boolean canAccess(Object target, Method method) {
		if (canAccess != null) {
			try {
				Boolean retValue = (Boolean) canAccess.invoke(method, target);
				return retValue;
			} catch (Exception e) {
				// ignore
			}
		}
		return true;
	}

    Object newInstance(Object[] args)
    {
        Constructor<?> ctor = ctor();
        try {
            try {
                return ctor.newInstance(args);
            } catch (IllegalAccessException ex) {
                if (!VMBridge.instance.tryToMakeAccessible(ctor)) {
                    throw Context.throwAsScriptRuntimeEx(ex);
                }
            }
            return ctor.newInstance(args);
        } catch (Exception ex) {
            throw Context.throwAsScriptRuntimeEx(ex);
        }
    }
    
	private static Method getAccessibleMethod(Object target, Class<?> cls, String name, Class<?>... parameterTypes) {
		try {
			Method method = cls.getMethod(name, parameterTypes);
			if (canAccess(target, method))
				return method;
		} catch (Exception e) {
		}
		return null;
	}

    private static Method searchAccessibleMethod(Method method, Class<?>[] params)
    {
        int modifiers = method.getModifiers();
        if (Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers)) {
            Class<?> c = method.getDeclaringClass();
            if (!Modifier.isPublic(c.getModifiers())) {
                String name = method.getName();
                Class<?>[] intfs = c.getInterfaces();
                for (int i = 0, N = intfs.length; i != N; ++i) {
                    Class<?> intf = intfs[i];
                    if (Modifier.isPublic(intf.getModifiers())) {
                        try {
                            return intf.getMethod(name, params);
                        } catch (NoSuchMethodException ex) {
                        } catch (SecurityException ex) {  }
                    }
                }
                for (;;) {
                    c = c.getSuperclass();
                    if (c == null) { break; }
                    if (Modifier.isPublic(c.getModifiers())) {
                        try {
                            Method m = c.getMethod(name, params);
                            int mModifiers = m.getModifiers();
                            if (Modifier.isPublic(mModifiers)
                                && !Modifier.isStatic(mModifiers))
                            {
                                return m;
                            }
                        } catch (NoSuchMethodException ex) {
                        } catch (SecurityException ex) {  }
                    }
                }
            }
        }
        return null;
    }

    private void readObject(ObjectInputStream in)
        throws IOException, ClassNotFoundException
    {
        in.defaultReadObject();
        Member member = readMember(in);
        if (member instanceof Method) {
            init((Method)member);
        } else {
            init((Constructor<?>)member);
        }
    }

    private void writeObject(ObjectOutputStream out)
        throws IOException
    {
        out.defaultWriteObject();
        writeMember(out, memberObject);
    }

    /**
     * Writes a Constructor or Method object.
     *
     * Methods and Constructors are not serializable, so we must serialize
     * information about the class, the name, and the parameters and
     * recreate upon deserialization.
     */
    private static void writeMember(ObjectOutputStream out, Member member)
        throws IOException
    {
        if (member == null) {
            out.writeBoolean(false);
            return;
        }
        out.writeBoolean(true);
        if (!(member instanceof Method || member instanceof Constructor))
            throw new IllegalArgumentException("not Method or Constructor");
        out.writeBoolean(member instanceof Method);
        out.writeObject(member.getName());
        out.writeObject(member.getDeclaringClass());
        if (member instanceof Method) {
            writeParameters(out, ((Method) member).getParameterTypes());
        } else {
            writeParameters(out, ((Constructor<?>) member).getParameterTypes());
        }
    }

    /**
     * Reads a Method or a Constructor from the stream.
     */
    private static Member readMember(ObjectInputStream in)
        throws IOException, ClassNotFoundException
    {
        if (!in.readBoolean())
            return null;
        boolean isMethod = in.readBoolean();
        String name = (String) in.readObject();
        Class<?> declaring = (Class<?>) in.readObject();
        Class<?>[] parms = readParameters(in);
        try {
            if (isMethod) {
                return declaring.getMethod(name, parms);
            } else {
                return declaring.getConstructor(parms);
            }
        } catch (NoSuchMethodException e) {
            throw new IOException("Cannot find member: " + e);
        }
    }

    private static final Class<?>[] primitives = {
        Boolean.TYPE,
        Byte.TYPE,
        Character.TYPE,
        Double.TYPE,
        Float.TYPE,
        Integer.TYPE,
        Long.TYPE,
        Short.TYPE,
        Void.TYPE
    };

    /**
     * Writes an array of parameter types to the stream.
     *
     * Requires special handling because primitive types cannot be
     * found upon deserialization by the default Java implementation.
     */
    private static void writeParameters(ObjectOutputStream out, Class<?>[] parms)
        throws IOException
    {
        out.writeShort(parms.length);
    outer:
        for (int i=0; i < parms.length; i++) {
            Class<?> parm = parms[i];
            boolean primitive = parm.isPrimitive();
            out.writeBoolean(primitive);
            if (!primitive) {
                out.writeObject(parm);
                continue;
            }
            for (int j=0; j < primitives.length; j++) {
                if (parm.equals(primitives[j])) {
                    out.writeByte(j);
                    continue outer;
                }
            }
            throw new IllegalArgumentException("Primitive " + parm +
                                               " not found");
        }
    }

    /**
     * Reads an array of parameter types from the stream.
     */
    private static Class<?>[] readParameters(ObjectInputStream in)
        throws IOException, ClassNotFoundException
    {
        Class<?>[] result = new Class[in.readShort()];
        for (int i=0; i < result.length; i++) {
            if (!in.readBoolean()) {
                result[i] = (Class<?>) in.readObject();
                continue;
            }
            result[i] = primitives[in.readByte()];
        }
        return result;
    }
}

