#version 330

// Adapt 天空盒管线片元着色器 —— Complementary Reimagined r5 天空系统移植
// （SHADER_STYLE=4 / SUN_MOON_STYLE=2 / CLOUD_STYLE=3 / NIGHT_STAR_AMOUNT=2）。
// 来源：lib/atmospherics/sky.glsl、stars.glsl、gbuffers_skybasic（程序化日月）、
// lib/atmospherics/clouds/reimaginedClouds.glsl + mainClouds.glsl + cloudCoord.glsl、
// lib/colors/skyColors.glsl + cloudColors.glsl + lightAndAmbientColors.glsl。
// 全部 uniform 因子从 AdaptLighting UBO 与 vanilla ColorModulator 推导；
// 云噪声图（colortex3.b）与月面噪声（noisetex）用整数 hash 程序化等价替代，
// 纯 Vulkan 单 pass，无前置 pass / 无纹理依赖。

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <adaptclient:adapt_lighting.glsl>

in vec3 adaptViewPos;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;

out vec4 fragColor;

// ---------- 通用小函数（Complementary commonFunctions.glsl） ----------
float sqrt1(float a) { return sqrt(a); }
vec3 sqrt1(vec3 a) { return sqrt(a); }
float pow2(float a) { return a * a; }
float pow4(float a) { float b = a * a; return b * b; }
float max0(float a) { return max(a, 0.0); }
float clamp01(float a) { return clamp(a, 0.0, 1.0); }
float smoothstep1(float a) { return a * a * (3.0 - 2.0 * a); }
float GetLuminance(vec3 color) { return dot(color, vec3(0.2126, 0.7152, 0.0722)); }

float GetHorizonFactor(float XdotU) {
    // SUN_MOON_HORIZON 关闭分支（Reimagined 默认）
    float horizon = min(XdotU + 1.0, 1.0);
    horizon *= horizon;
    horizon *= horizon;
    return horizon * horizon;
}

// ---------- 因子（common.glsl / skybasic） ----------
float SdotU;
float sunFactor;
float sunVisibility;
float sunVisibility2;
float shadowTime;
float noonFactor;
float nightFactor;
float invNoonFactor;
float invNoonFactor2;
float nightFactorM;
float rainFactor;
float rainFactor2;
float invRainFactor;
float vsBrightness;
float syncedTime;
vec3 SunVecView; // 真实太阳方向（视空间，不随 Beryl 夜间翻转）——天空/星星/日月专用

void computeFactors() {
    // 真实太阳方向：SUN_ANGLE 0=正午 → timeAngle 0.25 → 仰角 0 → 世界方向 (0,1,0)。
    // ViewRotCol 是 viewRotationMatrix（视图→世界）的三列，dot(列, worldDir) =
    // M^T * worldDir = 世界→视图 * worldDir = 视空间太阳方向（与 Beryl 一致）。
    float timeAngle = SkyFactors0.y;
    float elevAngle = (timeAngle - 0.25) * 6.28318530718;
    vec3 worldSunDir = vec3(sin(elevAngle), cos(elevAngle), 0.0);
    SunVecView = normalize(vec3(dot(ViewRotCol0.xyz, worldSunDir),
                                dot(ViewRotCol1.xyz, worldSunDir),
                                dot(ViewRotCol2.xyz, worldSunDir)));

    SdotU = dot(SunVecView, UpVector.xyz);
    sunFactor = SdotU < 0.0 ? clamp(SdotU + 0.375, 0.0, 0.75) / 0.75
                            : clamp(SdotU + 0.03125, 0.0, 0.0625) / 0.0625;
    sunVisibility = clamp(SdotU + 0.0625, 0.0, 0.125) / 0.125;
    sunVisibility2 = sunVisibility * sunVisibility;
    float shadowTimeVar1 = abs(sunVisibility - 0.5) * 2.0;
    shadowTime = shadowTimeVar1 * shadowTimeVar1;
    shadowTime *= shadowTime;
    noonFactor = sqrt(max(sin(timeAngle * 6.28318530718), 0.0));
    nightFactor = max(sin(timeAngle * -6.28318530718), 0.0);
    invNoonFactor = 1.0 - noonFactor;
    invNoonFactor2 = invNoonFactor * invNoonFactor;
    nightFactorM = pow2(sqrt1(nightFactor)) * 0.4;
    rainFactor = SkyFactors0.x;
    rainFactor2 = rainFactor * rainFactor;
    invRainFactor = 1.0 - rainFactor;
    // vsBrightness 由 MinAmbientLight = gamma*0.05+0.01 反推（Beryl 公式）
    vsBrightness = clamp((MinAmbientLight - 0.01) * 20.0, 0.0, 1.0);
    syncedTime = GameTime * 1200.0; // Complementary syncedTime 的帧内等价（世界时间漂移）
}

// ---------- skyColors.glsl（OVERWORLD） ----------
vec3 dayUpSkyColor;
vec3 dayMiddleSkyColor;
vec3 dayDownSkyColor;
vec3 sunsetDownSkyColorP;

void computeSkyColors() {
    vec3 skyColor = ColorModulator.rgb;
    vec3 skyColorSqrt = sqrt(skyColor);
    float invRainStrength2 = invRainFactor * invRainFactor;
    vec3 skyColorM = mix(max(skyColorSqrt, vec3(0.63, 0.67, 0.73)), skyColorSqrt, invRainStrength2);
    vec3 skyColorM2 = mix(max(skyColor, sunFactor * vec3(0.265, 0.295, 0.35)), skyColor, invRainStrength2);

    vec3 nuscWeatherM = vec3(0.1, 0.0, 0.1);
    vec3 nmscWeatherM = vec3(-0.1, -0.4, -0.6) + vec3(0.0, 0.06, 0.12) * noonFactor;
    vec3 ndscWeatherM = vec3(-0.15, -0.3, -0.42) + vec3(0.0, 0.02, 0.08) * noonFactor;

    vec3 noonUpSkyColor     = pow(skyColorM, vec3(2.9)) * (vec3(0.85, 0.92, 0.81) + rainFactor * nuscWeatherM);
    vec3 noonMiddleSkyColor = pow(skyColorM, vec3(1.5)) * (vec3(1.3) + rainFactor * nmscWeatherM)
                            + noonUpSkyColor * 0.65;
    vec3 noonDownSkyColor   = skyColorM * (vec3(0.9) + rainFactor * ndscWeatherM)
                            + noonUpSkyColor * 0.25;

    vec3 sunsetUpSkyColor     = skyColorM2 * (vec3(0.72, 0.522, 0.47) + vec3(0.1, 0.2, 0.35) * rainFactor2);
    vec3 sunsetMiddleSkyColor = skyColorM2 * (vec3(1.8, 1.3, 1.2) + vec3(0.15, 0.25, -0.05) * rainFactor2);
    sunsetDownSkyColorP       = vec3(1.45, 0.86, 0.5) - vec3(0.8, 0.3, 0.0) * rainFactor;
    vec3 sunsetDownSkyColor   = sunsetDownSkyColorP * 0.5 + 0.25 * sunsetMiddleSkyColor;

    dayUpSkyColor     = mix(noonUpSkyColor, sunsetUpSkyColor, invNoonFactor2);
    dayMiddleSkyColor = mix(noonMiddleSkyColor, sunsetMiddleSkyColor, invNoonFactor2);
    dayDownSkyColor   = mix(noonDownSkyColor, sunsetDownSkyColor, invNoonFactor2);

    vec3 nightColFactor      = 0.9 * vec3(0.07, 0.14, 0.24) * (1.0 - 0.5 * rainFactor) + skyColor;
    vec3 nightUpSkyColor     = pow(nightColFactor, vec3(0.90)) * 0.45;
    vec3 nightMiddleSkyColor = sqrt(nightUpSkyColor) * 0.65;
    vec3 nightDownSkyColor   = nightMiddleSkyColor * vec3(0.82, 0.82, 0.88);
}

// ---------- sky.glsl GetSky ----------
vec3 GetSky(float VdotU, float VdotS, float dither, bool doGlare, bool doGround) {
    float nightFactorSqrt2 = sqrt(nightFactor);
    float VdotSM1 = pow2(max(VdotS, 0.0));
    float VdotSM2 = pow2(VdotSM1);
    float VdotSM3 = pow2(pow2(max(-VdotS, 0.0)));
    float VdotSML = sunVisibility > 0.5 ? VdotS : -VdotS;

    float VdotUmax0 = max(VdotU, 0.0);
    float VdotUmax0M = 1.0 - pow2(VdotUmax0);

    vec3 nightUpSkyColor     = pow(0.9 * vec3(0.07, 0.14, 0.24) * (1.0 - 0.5 * rainFactor) + ColorModulator.rgb, vec3(0.90)) * 0.45;
    vec3 nightMiddleSkyColor = sqrt(nightUpSkyColor) * 0.65;
    vec3 nightDownSkyColor   = nightMiddleSkyColor * vec3(0.82, 0.82, 0.88);

    vec3 upColor = mix(nightUpSkyColor * (1.5 - 0.5 * nightFactorSqrt2 + nightFactorM * VdotSM3 * 1.5), dayUpSkyColor, sunFactor);
    vec3 middleColor = mix(nightMiddleSkyColor * (3.0 - 2.0 * nightFactorSqrt2), dayMiddleSkyColor * (1.0 + VdotSM2 * 0.3), sunFactor);
    vec3 downColor = mix(nightDownSkyColor, dayDownSkyColor, (sunFactor + sunVisibility) * 0.5);

    float VdotUM1 = pow2(1.0 - VdotUmax0);
    VdotUM1 = pow(VdotUM1, 1.0 - VdotSM2 * 0.4);
    VdotUM1 = mix(VdotUM1, 1.0, rainFactor2 * 0.15);
    vec3 finalSky = mix(upColor, middleColor, VdotUM1);

    float VdotUM2 = pow2(1.0 - abs(VdotU));
    VdotUM2 = VdotUM2 * VdotUM2 * (3.0 - 2.0 * VdotUM2);
    VdotUM2 *= (0.7 - nightFactorM + VdotSM1 * (0.3 + nightFactorM)) * invNoonFactor * sunFactor;
    finalSky = mix(finalSky, sunsetDownSkyColorP * (1.0 + VdotSM1 * 0.3), VdotUM2 * invRainFactor);

    float VdotUM3 = min(max0(-VdotU + 0.08) / 0.35, 1.0);
    VdotUM3 = smoothstep1(VdotUM3);
    vec3 scatteredGroundMixer = vec3(VdotUM3 * VdotUM3, sqrt1(VdotUM3), sqrt1(sqrt1(VdotUM3)));
    scatteredGroundMixer = mix(vec3(VdotUM3), scatteredGroundMixer, 0.75 - 0.5 * rainFactor);
    finalSky = mix(finalSky, downColor, scatteredGroundMixer);

    if (doGround)
        finalSky *= smoothstep1(pow2(1.0 + min(VdotU, 0.0)));

    if (doGlare) {
        if (0.0 < VdotSML) {
            float glareScatter = 3.0 * (2.0 - clamp01(VdotS * 1000.0));
            glareScatter *= 1.0 - 0.75 * rainFactor2;
            float VdotSM4 = pow(abs(VdotS), glareScatter);

            float visfactor = 0.075;
            float glare = visfactor / (1.0 - (1.0 - visfactor) * VdotSM4) - visfactor;
            glare *= 0.7;

            vec3 glareColor = mix(vec3(0.38, 0.4, 0.5) * 0.3, vec3(1.5, 0.7, 0.3) + vec3(0.0, 0.5, 0.5) * noonFactor, sunVisibility);
            glare *= 1.0 - 0.8 * rainFactor;
            glareColor = mix(glareColor, vec3(GetLuminance(glareColor)), 0.5 * rainFactor);

            finalSky += glareColor * glare * shadowTime;
        }
    }

    finalSky += (dither - 0.5) / 128.0;
    return finalSky;
}

// ---------- stars.glsl（程序化星星） ----------
float GetStarNoise(vec2 pos) {
    return fract(sin(dot(pos, vec2(12.9898, 4.1414))) * 43758.54953);
}

vec3 viewToWorld(vec3 viewDir) {
    // ViewRotCol* 是 viewRotationMatrix（视图→世界）的 GLSL 列：
    // ViewRotCol0*x + ViewRotCol1*y + ViewRotCol2*z = M * dir（视图→世界）。
    return ViewRotCol0.xyz * viewDir.x
         + ViewRotCol1.xyz * viewDir.y
         + ViewRotCol2.xyz * viewDir.z;
}

vec2 GetStarCoord(vec3 viewPos, float sphereness) {
    vec3 wpos = normalize(viewToWorld(normalize(viewPos)));
    // vanilla SkyRenderer 的星星旋转（Ry(+90) 再 Rx(-starAngle)），
    // 与 26.2 renderSunMoonAndStars 的 poseStack 旋转一致。
    float starAngle = SkyFactors0.w;
    float c = cos(starAngle);
    float s = sin(starAngle);
    vec3 yawed = vec3(wpos.z, wpos.y, -wpos.x);
    vec3 starLocal = vec3(yawed.x, c * yawed.y + s * yawed.z, -s * yawed.y + c * yawed.z);
    vec3 starCoord = starLocal / (starLocal.y + length(starLocal.xz) * sphereness);
    starCoord.x += 0.006 * syncedTime;
    return starCoord.xz;
}

vec3 GetStars(vec2 starCoord, float VdotU, float VdotS) {
    if (VdotU < 0.0) return vec3(0.0);

    starCoord *= 0.2;
    float starFactor = 1024.0;
    starCoord = floor(starCoord * starFactor) / starFactor;

    float star = 1.0;
    star *= GetStarNoise(starCoord.xy);
    star *= GetStarNoise(starCoord.xy + 0.1);
    star *= GetStarNoise(starCoord.xy + 0.23);

    star -= 0.7; // NIGHT_STAR_AMOUNT == 2（Reimagined 默认）
    star = max0(star);
    star *= star;

    star *= min(VdotU * 3.0, 1.0) * max0(1.0 - pow(abs(VdotS) * 1.002, 100.0));
    star *= invRainFactor * pow4(invNoonFactor2) * (1.0 - 0.5 * sunVisibility);

    return 40.0 * star * vec3(0.38, 0.4, 0.5);
}

// ---------- 程序化云噪声（替代 colortex3.b 噪声图） ----------
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash12(i);
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float cloudNoiseSample(vec2 coord) {
    // Complementary colortex3.b 的程序化等价：多八度 value noise（256 周期云图）。
    // coord 先 wrap 到 0..1（纹理 repeat 寻址等价），输入坐标压到 0..256 以内
    // 保证 hash 精度；基频 8 块/图对齐 Complementary 云图的大块形态。
    vec2 p = fract(coord) * 256.0;
    return valueNoise(p * 0.03125) * 0.50   // 8 周期大块
         + valueNoise(p * 0.09375) * 0.30   // 24 周期
         + valueNoise(p * 0.28125) * 0.20;  // 72 周期细节
}

// ---------- cloudCoord.glsl ----------
const float cloudNarrowness = 0.07;

vec2 GetRoundedCloudCoord(vec2 pos, float cloudRoundness) {
    vec2 coord = pos.yx + 0.5;
    vec2 signCoord = sign(coord);
    coord = abs(coord) + 1.0;
    vec2 iCoord = trunc(coord); // modf 等价（GLSL 330 无 modf）
    vec2 f = coord - iCoord;
    f = smoothstep(0.5 - cloudRoundness, 0.5 + cloudRoundness, f);
    coord = iCoord + f;
    return (coord - 0.5) * signCoord / 256.0;
}

vec3 ModifyTracePos(vec3 tracePos, int cloudAltitude) {
    float wind = syncedTime;
    tracePos.z -= wind;
    tracePos.x += float(cloudAltitude) * 64.0;
    tracePos.xz *= cloudNarrowness;
    return tracePos.xyz;
}

float InterleavedGradientNoiseForClouds(vec2 fragCoord) {
    float n = 52.9829189 * fract(0.06711056 * fragCoord.x + 0.00583715 * fragCoord.y);
    return fract(n);
}

vec3 nightMiddleSkyColorRef() {
    vec3 nightUpSkyColor = pow(0.9 * vec3(0.07, 0.14, 0.24) * (1.0 - 0.5 * rainFactor) + ColorModulator.rgb, vec3(0.90)) * 0.45;
    return sqrt(nightUpSkyColor) * 0.65;
}

// ---------- cloudColors.glsl（DEFERRED1 云用颜色） ----------
vec3 cloudLightColor;
vec3 cloudAmbientColor;

void computeCloudColors() {
    vec3 skyColor = ColorModulator.rgb;
    vec3 noonClearLightColor = vec3(0.65, 0.55, 0.375) * 2.05;
    vec3 noonClearAmbientColor = pow(skyColor, vec3(0.75)) * 0.85;
    vec3 sunsetClearLightColor = pow(vec3(0.64, 0.45, 0.3), vec3(1.5 + invNoonFactor)) * 5.0;
    vec3 sunsetClearAmbientColor = noonClearAmbientColor * vec3(1.21, 0.92, 0.76) * 0.95;
    vec3 nightClearLightColor = 0.9 * vec3(0.11, 0.14, 0.20);
    vec3 nightClearAmbientColor = 0.9 * vec3(0.09, 0.12, 0.17) * (1.55 + vsBrightness * 0.77);

    vec3 dayRainLightColor = vec3(0.21, 0.16, 0.13) * 0.85 + noonFactor * vec3(0.0, 0.02, 0.06);
    vec3 dayRainAmbientColor = vec3(0.2, 0.2, 0.25) * (1.8 + 0.5 * vsBrightness);
    vec3 nightRainLightColor = vec3(0.03, 0.035, 0.05) * (0.5 + 0.5 * vsBrightness);
    vec3 nightRainAmbientColor = vec3(0.16, 0.20, 0.3) * (0.75 + 0.6 * vsBrightness);

    vec3 dayLightColor = mix(sunsetClearLightColor, noonClearLightColor, noonFactor);
    vec3 dayAmbientColor = mix(sunsetClearAmbientColor, noonClearAmbientColor, noonFactor);
    vec3 clearLightColor = mix(nightClearLightColor, dayLightColor, sunVisibility2);
    vec3 clearAmbientColor = mix(nightClearAmbientColor, dayAmbientColor, sunVisibility2);

    vec3 rainLightColor = mix(nightRainLightColor, dayRainLightColor * 0.6, sunVisibility2) * 2.5;
    vec3 rainAmbientColor = mix(nightRainAmbientColor, dayRainAmbientColor * 1.4, sunVisibility2);

    vec3 lightColor = mix(clearLightColor, rainLightColor, rainFactor);
    vec3 ambientColor = mix(clearAmbientColor, rainAmbientColor, rainFactor);

    vec3 cloudRainColor = mix(nightMiddleSkyColorRef(), dayMiddleSkyColor, sunFactor);
    cloudAmbientColor = mix(ambientColor * (sunVisibility2 * (0.55 + 0.17 * noonFactor) + 0.35), cloudRainColor * 0.5, rainFactor);
    cloudLightColor = mix(lightColor * 1.3, cloudRainColor * 0.45, noonFactor * rainFactor);
    cloudAmbientColor *= 1.0 - 0.25 * rainFactor; // CLOUDS_REIMAGINED
}

// ---------- reimaginedClouds.glsl ----------
const float cloudStretch = 4.2;
const float cloudTallness = cloudStretch * 2.0;
const float cloudRoundness = 0.125;

bool GetCloudNoise(vec3 tracePos, inout vec3 tracePosM, int cloudAltitude) {
    tracePosM = ModifyTracePos(tracePos, cloudAltitude);
    vec2 coord = GetRoundedCloudCoord(tracePosM.xz, cloudRoundness);
    float noise = cloudNoiseSample(coord);
    float threshold = clamp(abs(float(cloudAltitude) - tracePos.y) / cloudStretch, 0.001, 0.999);
    threshold = pow2(pow2(pow2(threshold)));
    return noise > threshold * 0.5 + 0.25;
}

float Get2DCloudSample(vec2 pos) {
    return cloudNoiseSample(GetRoundedCloudCoord(pos, cloudRoundness));
}

vec4 GetVolumetricClouds(int cloudAltitude, float distanceThreshold, float skyFade, float skyMult0,
                         vec3 cameraPos, vec3 nPlayerPos, float lViewPosM, float VdotS, float VdotU, float dither) {
    vec4 volumetricClouds = vec4(0.0);

    float higherPlaneAltitude = float(cloudAltitude) + cloudStretch;
    float lowerPlaneAltitude = float(cloudAltitude) - cloudStretch;

    float lowerPlaneDistance = (lowerPlaneAltitude - cameraPos.y) / nPlayerPos.y;
    float higherPlaneDistance = (higherPlaneAltitude - cameraPos.y) / nPlayerPos.y;
    float minPlaneDistance = max(min(lowerPlaneDistance, higherPlaneDistance), 0.0);
    float maxPlaneDistance = max(lowerPlaneDistance, higherPlaneDistance);
    if (maxPlaneDistance < 0.0) return vec4(0.0);
    float planeDistanceDif = maxPlaneDistance - minPlaneDistance;

    float aboveClouds = clamp01((cameraPos.y - lowerPlaneAltitude) * 0.05);
    float skyFadeM = mix(skyFade, 1.0, 0.4 * max(aboveClouds, rainFactor2));

    int sampleCount = max(int(planeDistanceDif) / 8, 12);
    sampleCount = min(sampleCount, 64); // Adapt 性能上限（Complementary CLOUD_QUALITY 2 等效）

    float stepMult = planeDistanceDif / float(sampleCount);
    vec3 traceAdd = nPlayerPos * stepMult;
    vec3 tracePos = cameraPos + minPlaneDistance * nPlayerPos;
    tracePos += traceAdd * dither;
    tracePos.y -= traceAdd.y;

    for (int i = 0; i < sampleCount; i++) {
        tracePos += traceAdd;

        vec3 cloudPlayerPos = tracePos - cameraPos;
        float lTracePos = length(cloudPlayerPos);
        float lTracePosXZ = length(cloudPlayerPos.xz);
        float cloudMult = 1.0;
        if (lTracePosXZ > distanceThreshold) break;
        if (lTracePos > lViewPosM) {
            if (skyFade < 0.7) continue;
            else cloudMult = skyMult0;
        }

        vec3 tracePosM;
        if (GetCloudNoise(tracePos, tracePosM, cloudAltitude)) {
            float lightMult = 1.0;

            float cloudShading = 1.0 - (higherPlaneAltitude - tracePos.y) / cloudTallness;
            cloudShading = pow(max0(cloudShading), 0.8);
            float VdotSM1 = max0(sunVisibility > 0.5 ? VdotS : -VdotS);

            float cloudShadingM = 1.0 - pow2(cloudShading);
            float gradientNoise = InterleavedGradientNoiseForClouds(gl_FragCoord.xy);

            vec3 cLightPos = tracePosM;
            vec3 cLightPosAdd = SunVecView * vec3(0.08); // 视空间太阳方向（Complementary lightVec 等价）
            cLightPosAdd *= shadowTime;

            float light = 2.0;
            cLightPos += (1.0 + gradientNoise) * cLightPosAdd;
            light -= Get2DCloudSample(cLightPos.xz) * cloudShadingM;
            cLightPos += gradientNoise * cLightPosAdd;
            light -= Get2DCloudSample(cLightPos.xz) * cloudShadingM;

            float VdotSM2 = VdotSM1 * shadowTime * 0.25;
            VdotSM2 += 0.5 * cloudShading + 0.08;
            cloudShading = VdotSM2 * light * lightMult;

            vec3 colorSample = cloudAmbientColor * 0.95 * (1.0 - 0.35 * cloudShading) + cloudLightColor * 0.9 * (0.1 + cloudShading);
            vec3 cloudSkyColor = GetSky(VdotU, VdotS, dither, false, false);
            float distanceRatio = (distanceThreshold - lTracePosXZ) / distanceThreshold;
            float cloudFogFactor = pow2(clamp(distanceRatio, 0.0, 1.0)) * 0.707;
            cloudFogFactor = mix(1.0, cloudFogFactor, skyFadeM);
            colorSample *= 1.0 - 0.2 * (1.0 - skyFadeM) * max(sunVisibility2, nightFactor);
            colorSample = mix(cloudSkyColor, colorSample, cloudFogFactor * 0.707);

            float cloudDistanceFactor = clamp(distanceRatio, 0.0, 0.75);

            volumetricClouds.a = sqrt(cloudDistanceFactor * 1.33333) * cloudMult;
            volumetricClouds.rgb = colorSample;
            break;
        }
    }

    volumetricClouds.a *= 0.5 + 0.5 * skyFadeM;
    return volumetricClouds;
}

// ---------- main：Complementary gbuffers_skybasic + deferred1 云合成 ----------
void main() {
    computeFactors();
    computeSkyColors();

    float dither = InterleavedGradientNoiseForClouds(gl_FragCoord.xy);

    vec3 viewDir = normalize(adaptViewPos);
    float VdotU = dot(viewDir, UpVector.xyz);
    float VdotS = dot(viewDir, SunVecView);

    // 天空渐变 + glare（Complementary GetSky）
    vec3 color = GetSky(VdotU, VdotS, dither, true, false);

    // 程序化星星（原版星星由 STARS 管线 discard 移除）
    vec2 starCoord = GetStarCoord(adaptViewPos, 0.5);
    color += GetStars(starCoord, VdotU, VdotS);

    // ---- 程序化太阳/月亮（SUN_MOON_STYLE == 2）----
    float absVdotS = abs(VdotS);
    float sunSizeFactor1 = 0.9983;
    float sunSizeFactor2 = 588.235;
    if (absVdotS > sunSizeFactor1) {
        float sunMoonMixer = sqrt(sunSizeFactor2 * (absVdotS - sunSizeFactor1));
        sunMoonMixer *= 1.0 - rainFactor2;

        if (VdotS > 0.0) {
            sunMoonMixer = pow2(sunMoonMixer) * GetHorizonFactor(SdotU);
            color = mix(color, vec3(0.9, 0.5, 0.3) * 25.0, sunMoonMixer);
        } else {
            float horizonFactor = GetHorizonFactor(-SdotU);
            sunMoonMixer = max0(sunMoonMixer - 0.25) * 1.33333 * horizonFactor;

            // 月面噪声（noisetex.g 的程序化等价）
            vec2 moonNoiseCoord = GetStarCoord(adaptViewPos, 1.0) * 0.5 + 0.617;
            float moonNoise = valueNoise(moonNoiseCoord * 64.0)
                            + valueNoise(moonNoiseCoord * 160.0) * 0.7
                            + valueNoise(moonNoiseCoord * 320.0) * 0.5;
            moonNoise = max0(moonNoise - 0.75) * 1.7;
            vec3 moonColor = vec3(0.38, 0.4, 0.5) * (1.2 - (0.2 + 0.2 * sqrt1(nightFactor)) * moonNoise);

            float moonPhase = SkyFactors0.z;
            float timeAngle = SkyFactors0.y;
            if (moonPhase >= 1.0) {
                float moonPhaseOffset = 0.0;
                if (moonPhase != 4.0) {
                    moonPhaseOffset = 0.0055;
                    moonColor *= 8.5;
                } else moonColor *= 10.0;
                if (moonPhase > 4.0) {
                    moonPhaseOffset = -moonPhaseOffset;
                }

                float ang = fract(timeAngle - (0.25 + moonPhaseOffset));
                ang = (ang + (cos(ang * 3.14159265358979) * -0.5 + 0.5 - ang) / 3.0) * 6.28318530717959;
                // sunPathRotation = 0（Complementary 默认）
                vec3 worldMoonVec = vec3(-sin(ang), cos(ang), 0.0) * 2000.0;
                vec3 rawSunVec2 = vec3(dot(ViewRotCol0.xyz, worldMoonVec),
                                       dot(ViewRotCol1.xyz, worldMoonVec),
                                       dot(ViewRotCol2.xyz, worldMoonVec));

                float moonPhaseVdotS = dot(viewDir, normalize(rawSunVec2));
                sunMoonMixer *= pow2(1.0 - min(pow(abs(moonPhaseVdotS), 1000.0) * 2.2, 1.0));
            } else moonColor *= 4.0;

            color = mix(color, moonColor, sunMoonMixer);
        }
    }

    // Reimagined volumetric clouds run in the opaque-after/deferred fullscreen pass.
    // They must see opaque depth and cannot be evaluated correctly in a sky-dome draw.
    // SKY fan 的有限边界必须按 vanilla FogSkyEnd 淡出到 framebuffer clear 色，
    // 否则夜间会露出八边形天空穹接缝。
    fragColor = apply_fog(vec4(color, 1.0), sphericalVertexDistance,
            cylindricalVertexDistance, 0.0, FogSkyEnd, FogSkyEnd, FogSkyEnd, FogColor);
}
