package com.z1fire.forestwalk;

import android.opengl.GLES30;

import java.util.HashMap;

/** A linked GLSL program with cached uniform locations. */
final class Program {
    final int id;
    private final HashMap<String, Integer> locations = new HashMap<>();

    Program(String vertexSrc, String fragmentSrc) {
        int vs = compile(GLES30.GL_VERTEX_SHADER, vertexSrc);
        int fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSrc);
        id = GLES30.glCreateProgram();
        GLES30.glAttachShader(id, vs);
        GLES30.glAttachShader(id, fs);
        GLES30.glLinkProgram(id);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("Program link failed: " + GLES30.glGetProgramInfoLog(id));
        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
    }

    private static int compile(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(s);
            throw new RuntimeException("Shader compile failed: " + log + "\n" + src);
        }
        return s;
    }

    void use() {
        GLES30.glUseProgram(id);
    }

    int loc(String name) {
        Integer l = locations.get(name);
        if (l == null) {
            l = GLES30.glGetUniformLocation(id, name);
            locations.put(name, l);
        }
        return l;
    }

    void set1i(String n, int v) { GLES30.glUniform1i(loc(n), v); }
    void set1f(String n, float v) { GLES30.glUniform1f(loc(n), v); }
    void set2f(String n, float x, float y) { GLES30.glUniform2f(loc(n), x, y); }
    void set3f(String n, float x, float y, float z) { GLES30.glUniform3f(loc(n), x, y, z); }
    void set3f(String n, float[] v) { GLES30.glUniform3f(loc(n), v[0], v[1], v[2]); }
    void setMat4(String n, float[] m) { GLES30.glUniformMatrix4fv(loc(n), 1, false, m, 0); }
}
