package com.z1fire.forestwalk;

/** GLSL ES 3.00 sources. Lighting is done in linear space with ACES tone mapping at the end. */
final class Shaders {
    private Shaders() {}

    private static final String FS_HEADER = """
            #version 300 es
            precision highp float;
            precision highp sampler2DShadow;
            """;

    private static final String COMMON = """
            uniform vec3 uSunDir;
            uniform vec3 uSunCol;
            uniform vec3 uSkyAmb;
            uniform vec3 uGroundAmb;
            uniform vec3 uFogCol;
            uniform vec3 uCamPos;
            uniform float uFogDensity;
            uniform float uTime;
            uniform float uExposure;
            uniform mat4 uShadowMat;
            uniform sampler2DShadow uShadowMap;

            float hash12(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }
            float vnoise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), u.x),
                           mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), u.x), u.y);
            }
            vec3 srgb(vec3 c) { return pow(c, vec3(2.2)); }
            vec3 tonemap(vec3 c) {
                c *= uExposure;
                c = (c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14);
                return pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.2));
            }
            vec3 fogColor(vec3 d) {
                float mu = max(dot(d, uSunDir), 0.0);
                return uFogCol + uSunCol * (0.08 * pow(mu, 5.0) + 0.10 * pow(mu, 24.0));
            }
            vec3 applyFog(vec3 col, vec3 wp) {
                vec3 v = wp - uCamPos;
                float dist = length(v);
                float k = dist * uFogDensity;
                float f = 1.0 - exp(-k * k - dist * 0.0022);
                return mix(col, fogColor(v / max(dist, 0.001)), f);
            }
            float shadowAt(vec3 wp, vec3 n) {
                vec4 sp = uShadowMat * vec4(wp + n * 0.06, 1.0);
                vec3 p = sp.xyz;
                if (p.x <= 0.0 || p.x >= 1.0 || p.y <= 0.0 || p.y >= 1.0 || p.z >= 1.0) return 1.0;
                float ts = 1.0 / float(textureSize(uShadowMap, 0).x);
                float s = texture(uShadowMap, vec3(p.xy + vec2(-0.5, -0.5) * ts, p.z));
                s += texture(uShadowMap, vec3(p.xy + vec2(0.5, -0.5) * ts, p.z));
                s += texture(uShadowMap, vec3(p.xy + vec2(-0.5, 0.5) * ts, p.z));
                s += texture(uShadowMap, vec3(p.xy + vec2(0.5, 0.5) * ts, p.z));
                s *= 0.25;
                vec2 e = abs(p.xy - 0.5) * 2.0;
                return mix(s, 1.0, smoothstep(0.85, 1.0, max(e.x, e.y)));
            }
            """;

    // ------------------------------------------------------------------ terrain

    static final String TERRAIN_VS = """
            #version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNor;
            layout(location = 3) in vec4 aAttr;
            uniform mat4 uViewProj;
            out vec3 vWorld;
            out vec3 vNor;
            out vec4 vAttr;
            void main() {
                vWorld = aPos;
                vNor = aNor;
                vAttr = aAttr;
                gl_Position = uViewProj * vec4(aPos, 1.0);
            }
            """;

    static final String TERRAIN_FS = FS_HEADER + COMMON + """
            in vec3 vWorld;
            in vec3 vNor;
            in vec4 vAttr;   // x: trail, y: moisture, z: canopy occlusion
            out vec4 fragColor;

            float detail(vec2 p) { return vnoise(p * 2.3) * 0.6 + vnoise(p * 9.0 + 1.7) * 0.3; }

            void main() {
                vec2 p = vWorld.xz;
                vec3 Ng = normalize(vNor);
                float dist = distance(vWorld, uCamPos);
                float fade = 1.0 - smoothstep(15.0, 60.0, dist);
                float n1 = vnoise(p * 0.11);
                float n2 = vnoise(p * 0.55 + 7.3);
                float n3 = vnoise(p * 2.3 - 3.1);
                float n4 = vnoise(p * 9.0 + 1.7);
                float n5 = vnoise(p * 31.0);

                float e = 0.06;
                float h0 = detail(p);
                float hx = detail(p + vec2(e, 0.0));
                float hz = detail(p + vec2(0.0, e));
                vec3 N = normalize(Ng + vec3(h0 - hx, 0.0, h0 - hz) / e * 0.10 * fade);

                vec3 moss = vec3(0.26, 0.33, 0.11);
                vec3 litter = vec3(0.34, 0.25, 0.15);
                vec3 needles = vec3(0.40, 0.26, 0.14);
                vec3 soil = vec3(0.17, 0.13, 0.09);
                vec3 dirt = vec3(0.45, 0.36, 0.26);
                vec3 rock = vec3(0.46, 0.45, 0.43);

                float m = smoothstep(0.35, 0.7, n1 * 0.6 + n2 * 0.4 + (vAttr.y - 0.5) * 0.6);
                vec3 c = mix(litter, moss, m);
                c = mix(c, needles, smoothstep(0.55, 0.85, n2) * 0.7 * (1.0 - m));
                c = mix(c, soil, smoothstep(0.62, 0.9, n3) * 0.5);
                c *= 0.75 + 0.35 * n4 * fade + 0.25 * (n5 - 0.5) * fade + 0.15 * (1.0 - fade);
                float slope = 1.0 - Ng.y;
                c = mix(c, rock * (0.75 + 0.3 * n3 + 0.2 * n5), smoothstep(0.3, 0.5, slope + (n2 - 0.5) * 0.2));
                float trail = smoothstep(0.25, 0.6, vAttr.x + (n3 - 0.5) * 0.4);
                c = mix(c, dirt * (0.8 + 0.25 * n4 + 0.15 * n5), trail);

                vec3 albedo = srgb(c);
                float sh = shadowAt(vWorld, Ng);
                float ndl = max(dot(N, uSunDir), 0.0);
                vec3 amb = mix(uGroundAmb, uSkyAmb, N.y * 0.5 + 0.5) * vAttr.z;
                vec3 col = albedo * (uSunCol * ndl * sh + amb);
                col = applyFog(col, vWorld);
                fragColor = vec4(tonemap(col), 1.0);
            }
            """;

    // ------------------------------------------------------------------ instanced objects

    static final String OBJECT_VS = """
            #version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNor;
            layout(location = 2) in vec2 aUV;
            layout(location = 3) in vec4 aAttr;    // sway, isLeaf, ao, phase
            layout(location = 4) in vec4 iPosRot;  // x, y, z, rotation
            layout(location = 5) in vec4 iData;    // scale, tint, phase, extra
            uniform mat4 uViewProj;
            uniform float uTime;
            uniform vec2 uWind;
            uniform float uSwayAmp;
            uniform float uFadeDist;
            uniform vec3 uCamPos;
            out vec3 vWorld;
            out vec3 vNor;
            out vec2 vUV;
            out vec4 vAttr;
            out float vTint;
            void main() {
                float c = cos(iPosRot.w);
                float s = sin(iPosRot.w);
                float sc = iData.x;
                vec3 p = aPos * sc;
                if (uFadeDist > 0.0) {
                    float d = distance(iPosRot.xz, uCamPos.xz);
                    p *= 1.0 - smoothstep(uFadeDist * 0.7, uFadeDist, d);
                }
                vec3 w = vec3(c * p.x + s * p.z, p.y, -s * p.x + c * p.z) + iPosRot.xyz;
                vec3 n = vec3(c * aNor.x + s * aNor.z, aNor.y, -s * aNor.x + c * aNor.z);
                float ph = iData.z + aAttr.w;
                float sway = aAttr.x;
                float t = uTime;
                float big = sin(t * 0.8 + ph + iPosRot.x * 0.05 + iPosRot.z * 0.04) * 0.6
                          + sin(t * 1.9 + ph * 1.3) * 0.3 + 0.35;
                w.xz += uWind * (big * sway * uSwayAmp * sc);
                vec3 flutter = vec3(sin(t * 5.7 + w.x * 2.3 + ph), sin(t * 6.9 + w.y * 2.9 + ph * 2.0),
                                    sin(t * 6.3 + w.z * 2.5)) * 0.04 * length(uWind);
                w += flutter * aAttr.y * sway * sc;
                vWorld = w;
                vNor = n;
                vUV = aUV;
                vAttr = aAttr;
                vTint = iData.y;
                gl_Position = uViewProj * vec4(w, 1.0);
            }
            """;

    static final String OBJECT_FS = FS_HEADER + COMMON + """
            in vec3 vWorld;
            in vec3 vNor;
            in vec2 vUV;
            in vec4 vAttr;
            in float vTint;
            uniform sampler2D uLeafTex;
            uniform vec3 uBarkCol;
            uniform int uBarkType;      // 0 furrowed, 1 birch, 2 smooth, 3 pine, 4 rock
            uniform vec3 uLeafTint;
            uniform float uMsaa;
            uniform float uTranslucency;
            out vec4 fragColor;

            vec3 barkAlbedo() {
                vec2 uv = vUV;
                float height = uv.y / 0.6;
                if (uBarkType == 1) {
                    float n = vnoise(vec2(uv.x * 6.0, uv.y * 18.0));
                    float lent = smoothstep(0.72, 0.8, vnoise(vec2(uv.x * 3.0, uv.y * 30.0)));
                    float patches = smoothstep(0.62, 0.7, vnoise(uv * vec2(4.0, 3.0)));
                    vec3 c = uBarkCol * (0.9 + 0.1 * n);
                    c = mix(c, vec3(0.08, 0.07, 0.06), max(lent * 0.9, patches * 0.85));
                    return mix(vec3(0.12, 0.10, 0.09), c, smoothstep(0.2, 1.2, height));
                } else if (uBarkType == 2) {
                    float n = vnoise(uv * vec2(8.0, 3.0)) * 0.6 + vnoise(uv * vec2(30.0, 10.0)) * 0.4;
                    float lichen = smoothstep(0.55, 0.8, vnoise(uv * vec2(2.0, 1.2)));
                    return uBarkCol * (0.8 + 0.35 * n) * mix(vec3(1.0), vec3(0.75, 0.9, 0.6), lichen);
                }
                float f = vnoise(vec2(uv.x * 14.0, uv.y * 2.5));
                float g = vnoise(vec2(uv.x * 40.0, uv.y * 9.0));
                float furrow = smoothstep(0.35, 0.65, f);
                vec3 c = uBarkCol * (0.55 + 0.6 * furrow) * (0.85 + 0.3 * g);
                if (uBarkType == 3) c = mix(c, vec3(0.62, 0.34, 0.18) * (0.8 + 0.4 * g), smoothstep(8.0, 14.0, height));
                return c;
            }

            vec3 rockAlbedo(vec3 n) {
                vec3 p = vWorld;
                float a = vnoise(p.xz * 2.0 + p.y * 1.3);
                float b = vnoise(p.xy * 7.0 + p.z * 3.1);
                vec3 c = vec3(0.42, 0.41, 0.39) * (0.7 + 0.3 * a + 0.2 * b);
                float moss = smoothstep(0.35, 0.8, n.y + (a - 0.5) * 0.6);
                return mix(c, vec3(0.20, 0.28, 0.08) * (0.7 + 0.5 * b), moss);
            }

            void main() {
                vec4 tex = texture(uLeafTex, vUV);
                vec2 sz = vec2(textureSize(uLeafTex, 0));
                vec2 gx = dFdx(vUV * sz);
                vec2 gy = dFdy(vUV * sz);
                float lod = max(0.0, 0.5 * log2(max(dot(gx, gx), dot(gy, gy)) + 1e-6));
                float a = tex.a * (1.0 + lod * 0.22);
                float aw = max(fwidth(a), 1e-4);
                bool leaf = vAttr.y > 0.5;
                vec3 N = normalize(vNor);
                vec3 albedo;
                float alpha = 1.0;
                if (leaf) {
                    float as = clamp((a - 0.5) / aw + 0.5, 0.0, 1.0);
                    if (uMsaa > 0.5) {
                        if (as < 0.02) discard;
                        alpha = as;
                    } else if (a < 0.5) {
                        discard;
                    }
                    albedo = srgb(tex.rgb) * uLeafTint * vTint;
                } else if (uBarkType == 4) {
                    albedo = srgb(rockAlbedo(N)) * vTint;
                } else {
                    albedo = srgb(barkAlbedo()) * (0.8 + 0.2 * vTint);
                }
                vec3 V = normalize(uCamPos - vWorld);
                float sh = shadowAt(vWorld, N);
                float ndl = dot(N, uSunDir);
                float diff = leaf ? clamp(ndl * 0.55 + 0.45, 0.0, 1.0) : max(ndl, 0.0);
                vec3 amb = mix(uGroundAmb, uSkyAmb, N.y * 0.5 + 0.5) * vAttr.z;
                vec3 col = albedo * (uSunCol * diff * sh * mix(1.0, vAttr.z, 0.5) + amb);
                if (leaf) {
                    float tr = pow(max(dot(-V, uSunDir), 0.0), 4.0) * uTranslucency;
                    col += albedo * uSunCol * vec3(0.9, 1.0, 0.45) * tr * sh;
                }
                col = applyFog(col, vWorld);
                fragColor = vec4(tonemap(col), alpha);
            }
            """;

    static final String SHADOW_FS = """
            #version 300 es
            precision mediump float;
            in vec2 vUV;
            in vec4 vAttr;
            uniform sampler2D uLeafTex;
            void main() {
                if (vAttr.y > 0.5 && texture(uLeafTex, vUV).a < 0.5) discard;
            }
            """;

    // ------------------------------------------------------------------ sky

    static final String SKY_VS = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            out vec2 vNdc;
            void main() {
                vNdc = aPos;
                gl_Position = vec4(aPos, 1.0, 1.0);
            }
            """;

    static final String SKY_FS = FS_HEADER + COMMON + """
            in vec2 vNdc;
            uniform mat4 uInvViewProj;
            out vec4 fragColor;
            void main() {
                vec4 a = uInvViewProj * vec4(vNdc, 1.0, 1.0);
                vec3 d = normalize(a.xyz / a.w - uCamPos);
                float y = max(d.y, 0.0);
                vec3 zenith = vec3(0.16, 0.33, 0.72);
                vec3 horizon = vec3(0.62, 0.74, 0.86);
                vec3 col = mix(horizon, zenith, pow(y, 0.5));
                float mu = max(dot(d, uSunDir), 0.0);
                col += uSunCol * (0.05 * pow(mu, 6.0) + 0.25 * pow(mu, 64.0));
                if (d.y > 0.0) {
                    vec2 cp = d.xz / (d.y + 0.08) * 1.2 + vec2(uTime * 0.004, uTime * 0.0015);
                    float cl = vnoise(cp) * 0.5 + vnoise(cp * 2.1) * 0.25 + vnoise(cp * 4.3) * 0.125 + vnoise(cp * 8.7) * 0.0625;
                    cl = smoothstep(0.45, 0.8, cl) * smoothstep(0.0, 0.25, d.y);
                    vec3 cc = mix(vec3(0.75, 0.78, 0.84), vec3(1.25, 1.18, 1.05), mu * 0.5 + 0.5);
                    col = mix(col, cc * 1.2, cl * 0.85);
                }
                col += uSunCol * 6.0 * smoothstep(0.99955, 0.9999, mu);
                col = mix(fogColor(d), col, smoothstep(0.0, 0.22, d.y));
                fragColor = vec4(tonemap(col), 1.0);
            }
            """;
}
