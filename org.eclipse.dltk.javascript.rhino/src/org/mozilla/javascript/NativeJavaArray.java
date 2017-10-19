/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.lang.reflect.Array;

/**
 * This class reflects Java arrays into the JavaScript environment.
 *
 * @author Mike Shaver
 * @see NativeJavaClass
 * @see NativeJavaObject
 * @see NativeJavaPackage
 */

public class NativeJavaArray extends NativeJavaObject
{
    static final long serialVersionUID = -924022554283675333L;

    @Override
    public String getClassName() {
        return "JavaArray";
    }

    public static NativeJavaArray wrap(Scriptable scope, Object array) {
        return new NativeJavaArray(scope, array);
    }

    @Override
    public Object unwrap() {
        return array;
    }

    public NativeJavaArray(Scriptable scope, Object array) {
        super(scope, null, ScriptRuntime.ObjectClass);
        Class<?> cl = array.getClass();
        if (!cl.isArray()) {
            throw new RuntimeException("Array expected");
        }
        this.array = array;
        this.length = Array.getLength(array);
        this.cls = cl.getComponentType();
    }

    @Override
    public boolean has(String id, Scriptable start) {
        return id.equals("length") || super.has(id, start);
    }

    @Override
    public boolean has(int index, Scriptable start) {
    	// TODO is this has change really needed, it doesn't has it currently
    	// but it can be set..
    	return 0 <= index /* && index < length */;
    }

    @Override
    public Object get(String id, Scriptable start) {
        if (id.equals("length"))
            return Integer.valueOf(length);
        Object result = super.get(id, start);
        if (result == NOT_FOUND &&
            !ScriptableObject.hasProperty(getPrototype(), id))
        {
            throw Context.reportRuntimeError2(
                "msg.java.member.not.found", array.getClass().getName(), id);
        }
        return result;
    }

    @Override
    public Object get(int index, Scriptable start) {
        if (0 <= index && index < length) {
            Context cx = Context.getContext();
            Object obj = Array.get(array, index);
            return cx.getWrapFactory().wrap(cx, this, obj, cls);
        }
        return Undefined.instance;
    }

    @Override
    public void put(String id, Scriptable start, Object value) {
    	// Ignore assignments to "length"--it's readonly.
    	if (!id.equals("length"))
    		super.put(id, start, value);
    	else if (id.equals("length")) {
    		int prevLength = length;
    		length = ((Number) value).intValue();
    		if (prevLength != length) {
    			Object tmp = Array.newInstance(array.getClass()
    					.getComponentType(), length);
    			System.arraycopy(array, 0, tmp, 0, Math.min(prevLength, length));
    			array = tmp;
    		}
    	}
    }

    @Override
    public void put(int index, Scriptable start, Object value) {
    	if (0 <= index /* && index < length */) {
    		if (index >= length) {
    			put("length", start, new Integer(index + 1));
    			int prevLength = Array.getLength(array);
    			if (index > prevLength) {
    				Object tmp = Array.newInstance(array.getClass()
    						.getComponentType(), length);
    				System.arraycopy(array, 0, tmp, 0,
    						Math.min(prevLength, length));
    				array = tmp;
    			}
    		}
    		Array.set(array, index, Context.jsToJava(value, cls));
    		return;
    	} else {
    		throw Context.reportRuntimeError2(
    				"msg.java.array.index.out.of.bounds",
    				String.valueOf(index), String.valueOf(length - 1));
    	}
    }

    @Override
    public Object getDefaultValue(Class<?> hint) {
    	if (hint == null || hint == ScriptRuntime.StringClass) {

    		try {
    			int size = Array.getLength(array);

    			StringBuffer sb = new StringBuffer();
    			sb.append("["); //$NON-NLS-1$
    			size = size > 100 ? 100 : size;
    			for (int i = 0; i < size; i++) {
    				sb.append(Array.get(array, i));
    				sb.append(","); //$NON-NLS-1$
    			}
    			if (sb.length() > 1) {
    				sb.setLength(sb.length() - 1);
    			}
    			sb.append("]"); //$NON-NLS-1$
    			return sb.toString();

    		} catch (Exception e) {
    			return array.toString();
    		}
    	}
    	if (hint == ScriptRuntime.BooleanClass)
    		return Boolean.TRUE;
    	if (hint == ScriptRuntime.NumberClass)
    		return ScriptRuntime.NaNobj;
    	return this;
    }

    @Override
    public Object[] getIds() {
        Object[] result = new Object[length];
        int i = length;
        while (--i >= 0)
            result[i] = Integer.valueOf(i);
        return result;
    }

    @Override
    public boolean hasInstance(Scriptable value) {
        if (!(value instanceof Wrapper))
            return false;
        Object instance = ((Wrapper)value).unwrap();
        return cls.isInstance(instance);
    }

    @Override
    public Scriptable getPrototype() {
        if (prototype == null) {
            prototype =
                ScriptableObject.getArrayPrototype(this.getParentScope());
        }
        return prototype;
    }

    Object array;
    int length;
    Class<?> cls;
}
