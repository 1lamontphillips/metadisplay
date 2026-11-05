package com.lamontphillips.metadisplay.transporttest;

/** Pure column-major geometry for a native camera SurfaceTexture, with no sensor rotation replay. */
public final class CameraViewTransform {
  private CameraViewTransform() {}

  public static final class InvalidTransformException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private InvalidTransformException() { super("CAMERA_VIEW_INVALID"); }
  }

  public static final class Result {
    public final float[] textureMatrix;
    public final float[] vertexMatrix;
    public final double effectiveSourceAspect;
    public final double cropFractionX;
    public final double cropFractionY;
    public final double vertexScaleX;
    public final double vertexScaleY;
    /** Whether the native SurfaceTexture x axis maps to the source's y axis. */
    public final boolean surfaceAxesSwapped;
    private Result(float[] texture, float[] vertex, double aspect, double cropX, double cropY,
        double scaleX, double scaleY, boolean swapped) {
      textureMatrix = texture;
      vertexMatrix = vertex;
      effectiveSourceAspect = aspect;
      cropFractionX = cropX;
      cropFractionY = cropY;
      vertexScaleX = scaleX;
      vertexScaleY = scaleY;
      surfaceAxesSwapped = swapped;
    }
  }

  public static Result calculate(float[] surfaceMatrix, int captureWidth, int captureHeight,
      int outputWidth, int outputHeight, int displayRotationDegrees, int manualQuarterTurns,
      boolean fillFrame) {
    if (surfaceMatrix == null || surfaceMatrix.length != 16 || captureWidth <= 0 || captureHeight <= 0
        || outputWidth <= 0 || outputHeight <= 0 || (displayRotationDegrees != 0
        && displayRotationDegrees != 90 && displayRotationDegrees != 180 && displayRotationDegrees != 270)) {
      throw invalid();
    }
    float[] source = surfaceMatrix.clone();
    for (float value : source) if (!Float.isFinite(value)) throw invalid();
    for (int index : new int[] {2, 3, 6, 7, 8, 9, 11, 14}) {
      if (Math.abs(source[index]) > 0.000001) throw invalid();
    }
    if (Math.abs(source[10] - 1) > 0.000001 || Math.abs(source[15] - 1) > 0.000001) throw invalid();
    for (int x = 0; x <= 1; x++) for (int y = 0; y <= 1; y++) {
      double u = source[0] * x + source[4] * y + source[12];
      double v = source[1] * x + source[5] * y + source[13];
      if (u < -0.00001 || u > 1.00001 || v < -0.00001 || v > 1.00001) throw invalid();
    }
    int turns = Math.floorMod(Math.floorMod(manualQuarterTurns, 4) - displayRotationDegrees / 90, 4);
    float[] rotation = identity();
    switch (turns) {
      case 1:
        rotation[0] = 0; rotation[1] = 1; rotation[4] = -1; rotation[5] = 0; rotation[12] = 1;
        break;
      case 2:
        rotation[0] = -1; rotation[5] = -1; rotation[12] = 1; rotation[13] = 1;
        break;
      case 3:
        rotation[0] = 0; rotation[1] = -1; rotation[4] = 1; rotation[5] = 0; rotation[13] = 1;
        break;
      default: break;
    }
    float[] oriented = multiply(source, rotation);
    double xx = (double) oriented[0] * captureWidth;
    double xy = (double) oriented[1] * captureHeight;
    double yx = (double) oriented[4] * captureWidth;
    double yy = (double) oriented[5] * captureHeight;
    double lengthX = Math.hypot(xx, xy);
    double lengthY = Math.hypot(yx, yy);
    if (lengthX <= 0 || lengthY <= 0 || !Double.isFinite(lengthX) || !Double.isFinite(lengthY)
        || Math.abs(xx * yx + xy * yy) > lengthX * lengthY * 0.0001) throw invalid();
    double aspect = lengthX / lengthY;
    double destination = (double) outputWidth / outputHeight;
    double cropX = 1, cropY = 1, scaleX = 1, scaleY = 1;
    if (fillFrame) {
      if (aspect > destination) cropX = destination / aspect;
      else cropY = aspect / destination;
    } else {
      if (aspect < destination) scaleX = aspect / destination;
      else scaleY = destination / aspect;
    }
    float[] crop = identity();
    crop[0] = (float) cropX;
    crop[5] = (float) cropY;
    crop[12] = (float) ((1 - cropX) / 2);
    crop[13] = (float) ((1 - cropY) / 2);
    float[] vertex = identity();
    vertex[0] = (float) scaleX;
    vertex[5] = (float) scaleY;
    boolean swapped = Math.abs(source[1]) > Math.abs(source[0]);
    return new Result(multiply(oriented, crop), vertex, aspect, cropX, cropY, scaleX, scaleY, swapped);
  }

  private static InvalidTransformException invalid() { return new InvalidTransformException(); }

  private static float[] identity() {
    float[] result = new float[16];
    result[0] = result[5] = result[10] = result[15] = 1;
    return result;
  }

  private static float[] multiply(float[] left, float[] right) {
    float[] result = new float[16];
    for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++) {
      double value = 0;
      for (int k = 0; k < 4; k++) value += (double) left[k * 4 + row] * right[column * 4 + k];
      result[column * 4 + row] = (float) value;
    }
    return result;
  }
}
