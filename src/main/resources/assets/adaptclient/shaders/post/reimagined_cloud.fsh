#version 330

// Native Vulkan port of CLOUDS_REIMAGINED.
// Runs after opaque geometry and before translucent terrain/water. The cloud density
// is the original repeat+linear cloud-water.png blue channel, not generated noise.

#moj_import <adaptclient:adapt_lighting.glsl>

in vec2 texCoord;

uniform sampler2D SceneSampler;
uniform sampler2D OpaqueDepthSampler;
uniform sampler2D CloudDensitySampler;

layout(std140) uniform CloudFrame {
    mat4 ProjectionInverse;
    mat4 ViewRotationInverse;
    vec4 CameraWorldPosition;
    vec4 CloudParams;       // x=altitude y=half thickness z=render distance w=frame index
    vec4 TrueSunDirection;
    vec4 WeatherTime;       // x=rain y=timeAngle z=thunder w=partial tick
};

out vec4 fragColor;

float saturate(float value) {
    return clamp(value, 0.0, 1.0);
}

float pow2(float value) {
    return value * value;
}

float max0(float value) {
    return max(value, 0.0);
}

float smoothstep1(float value) {
    return value * value * (3.0 - 2.0 * value);
}

// , preserving its rounded-cell density coordinates.
vec2 roundedCloudCoord(vec2 position, float roundness) {
    vec2 coord = position.yx + 0.5;
    vec2 coordSign = sign(coord);
    coord = abs(coord) + 1.0;
    vec2 integerPart = trunc(coord);
    vec2 fraction = coord - integerPart;
    fraction = smoothstep(0.5 - roundness, 0.5 + roundness, fraction);
    coord = integerPart + fraction;
    return (coord - 0.5) * coordSign / 256.0;
}

vec3 modifyTracePosition(vec3 tracePosition, int cloudAltitude, float syncedTime) {
    tracePosition.z -= syncedTime;
    tracePosition.x += float(cloudAltitude) * 64.0;
    tracePosition.xz *= 0.07;
    return tracePosition;
}

float cloudDensity(vec2 coord) {
    return texture(CloudDensitySampler, coord).b;
}

bool cloudOccupied(vec3 tracePosition, int cloudAltitude, float halfThickness) {
    vec3 modifiedPosition = modifyTracePosition(tracePosition, cloudAltitude, WeatherTime.y * 1200.0);
    float density = cloudDensity(roundedCloudCoord(modifiedPosition.xz, 0.125));
    float vertical = clamp(abs(float(cloudAltitude) - tracePosition.y) / halfThickness, 0.001, 0.999);
    vertical = pow2(pow2(pow2(vertical)));
    return density > vertical * 0.5 + 0.25;
}

float interleavedGradientNoise() {
    float noise = 52.9829189 * fract(0.06711056 * gl_FragCoord.x + 0.00583715 * gl_FragCoord.y);
    return fract(noise + 0.61803398875 * mod(CloudParams.w, 3600.0));
}

void main() {
    // Native reversed-Z: cleared sky pixels are zero. Opaque terrain/entities must
    // remain in front of clouds, exactly as 's depthtex0/skyFade branch.
    float opaqueDepth = texture(OpaqueDepthSampler, texCoord).r;
    if (opaqueDepth > 0.00001) {
        discard;
    }

    vec2 ndc = texCoord * 2.0 - 1.0;
    vec4 viewPosition = ProjectionInverse * vec4(ndc, 1.0, 1.0);
    vec3 viewDirection = normalize(viewPosition.xyz / viewPosition.w);
    vec3 worldDirection = normalize((ViewRotationInverse * vec4(viewDirection, 0.0)).xyz);

    float altitude = CloudParams.x;
    float halfThickness = CloudParams.y;
    float lowerPlane = altitude - halfThickness;
    float upperPlane = altitude + halfThickness;
    vec3 cameraPosition = CameraWorldPosition.xyz;

    if (abs(worldDirection.y) < 0.00001) {
        discard;
    }

    float lowerDistance = (lowerPlane - cameraPosition.y) / worldDirection.y;
    float upperDistance = (upperPlane - cameraPosition.y) / worldDirection.y;
    float minDistance = max(min(lowerDistance, upperDistance), 0.0);
    float maxDistance = max(lowerDistance, upperDistance);
    if (maxDistance < 0.0) {
        discard;
    }

    float segmentLength = maxDistance - minDistance;
    // CLOUD_QUALITY=2: max(int(length)/8, 12). Keep a bounded native
    // iteration count for Vulkan/MoltenVK while preserving 8-block sampling nearby.
    int sampleCount = min(max(int(segmentLength) / 8, 12), 128);
    float stepLength = segmentLength / float(sampleCount);
    vec3 traceStep = worldDirection * stepLength;
    vec3 tracePosition = cameraPosition + worldDirection * minDistance;
    float dither = interleavedGradientNoise();
    tracePosition += traceStep * dither;
    tracePosition.y -= traceStep.y;

    float timeAngle = WeatherTime.y;
    float noonFactor = sqrt(max(sin(timeAngle * 6.28318530718), 0.0));
    float nightFactor = max(sin(-timeAngle * 6.28318530718), 0.0);
    float rainFactor = WeatherTime.x;
    float rainFactor2 = rainFactor * rainFactor;

    float SdotU = dot(TrueSunDirection.xyz, UpVector.xyz);
    float sunFactor = SdotU < 0.0 ? clamp(SdotU + 0.375, 0.0, 0.75) / 0.75
                                  : clamp(SdotU + 0.03125, 0.0, 0.0625) / 0.0625;
    float sunVisibility = clamp(SdotU + 0.0625, 0.0, 0.125) / 0.125;
    float sunVisibility2 = sunVisibility * sunVisibility;
    float shadowTime = pow2(pow2(abs(sunVisibility - 0.5) * 2.0));

    vec3 sceneColor = texture(SceneSampler, texCoord).rgb;
    vec3 nightCloudLight = 0.9 * vec3(0.11, 0.14, 0.20);
    vec3 nightAmbient = 0.9 * vec3(0.09, 0.12, 0.17)
            * (1.55 + clamp((MinAmbientLight - 0.01) * 20.0, 0.0, 1.0) * 0.77);
    vec3 dayCloudLight = mix(pow(vec3(0.64, 0.45, 0.3), vec3(1.5 + (1.0 - noonFactor))) * 5.0,
            vec3(0.65, 0.55, 0.375) * 2.05, noonFactor);
    vec3 dayAmbient = pow(max(sceneColor, vec3(0.001)), vec3(0.75)) * 0.85;
    vec3 cloudLight = mix(nightCloudLight, dayCloudLight, sunVisibility2);
    vec3 cloudAmbient = mix(nightAmbient, dayAmbient, sunVisibility2);
    vec3 rainLight = mix(vec3(0.03, 0.035, 0.05),
            (vec3(0.21, 0.16, 0.13) * 0.85 + noonFactor * vec3(0.0, 0.02, 0.06)) * 0.6,
            sunVisibility2) * 2.5;
    vec3 rainAmbient = mix(vec3(0.16, 0.20, 0.30),
            vec3(0.20, 0.20, 0.25) * 1.4, sunVisibility2);
    cloudLight = mix(cloudLight, rainLight, rainFactor);
    cloudAmbient = mix(cloudAmbient, rainAmbient, rainFactor);

    float VdotS = dot(viewDirection, TrueSunDirection.xyz);
    float VdotSM1 = max0(sunVisibility > 0.5 ? VdotS : -VdotS);
    float distanceThreshold = 4000.0;

    for (int sampleIndex = 0; sampleIndex < sampleCount; ++sampleIndex) {
        tracePosition += traceStep;
        vec3 delta = tracePosition - cameraPosition;
        float horizontalDistance = length(delta.xz);
        if (horizontalDistance > distanceThreshold) {
            break;
        }
        if (!cloudOccupied(tracePosition, int(altitude), halfThickness)) {
            continue;
        }

        float shading = 1.0 - (upperPlane - tracePosition.y) / (halfThickness * 2.0);
        shading = pow(max0(shading), 0.8);
        float shadingMask = 1.0 - pow2(shading);
        vec3 modifiedLightPosition = modifyTracePosition(tracePosition, int(altitude), WeatherTime.y * 1200.0);
        vec3 lightStep = TrueSunDirection.xyz * 0.08 * shadowTime;
        float light = 2.0;
        modifiedLightPosition += (1.0 + dither) * lightStep;
        light -= cloudDensity(roundedCloudCoord(modifiedLightPosition.xz, 0.125)) * shadingMask;
        modifiedLightPosition += dither * lightStep;
        light -= cloudDensity(roundedCloudCoord(modifiedLightPosition.xz, 0.125)) * shadingMask;
        shading = (VdotSM1 * shadowTime * 0.25 + 0.5 * shading + 0.08) * light;

        vec3 color = cloudAmbient * 0.95 * (1.0 - 0.35 * shading)
                + cloudLight * 0.9 * (0.1 + shading);
        float distanceRatio = (distanceThreshold - horizontalDistance) / distanceThreshold;
        float cloudFog = pow2(clamp(distanceRatio, 0.0, 1.0)) * 0.707;
        color = mix(sceneColor, color, cloudFog * 0.707);
        color *= 1.0 - 0.2 * max(sunVisibility2, nightFactor);

        float alpha = sqrt(clamp(distanceRatio, 0.0, 0.75) * 1.33333);
        fragColor = vec4(color, alpha);
        return;
    }

    discard;
}
