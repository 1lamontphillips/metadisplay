package com.lamontphillips.metadisplay.transporttest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Independent point/marker/pixel-circle geometry checks; no Android or physical-camera claims. */
public final class CameraViewTransformTest {
  private static final double EPS = 0.000006;
  private static long scenarios;

  public static void main(String[] args) {
    List<NamedCase> tests = new ArrayList<>();
    for (int nativeTurn = 0; nativeTurn < 4; nativeTurn++) {
      for (int displayTurn = 0; displayTurn < 4; displayTurn++) {
        final int nativeRotation = nativeTurn;
        final int displayRotation = displayTurn;
        tests.add(test("native " + nativeTurn * 90 + " / display " + displayTurn * 90
            + ": flips crops all manual turns FILL and FIT", () -> family(nativeRotation, displayRotation)));
      }
    }
    tests.add(test("known landscape and portrait FILL/FIT pixel ratios", CameraViewTransformTest::knownRatios));
    tests.add(test("manual positive turn rotates asymmetric image markers clockwise", CameraViewTransformTest::clockwiseMarkers));
    tests.add(test("display positive turn rotates asymmetric image markers counterclockwise", CameraViewTransformTest::displayMarkers));
    tests.add(test("matching manual and display turns cancel without replaying native rotation", CameraViewTransformTest::cancelTurns));
    tests.add(test("native quarter-turn matrix is applied exactly once", CameraViewTransformTest::nativeAppliedOnce));
    tests.add(test("raw native axis swap flag ignores additional view rotation", CameraViewTransformTest::rawAxisSwap));
    tests.add(test("off-center native crop retains its original center", CameraViewTransformTest::offCenter));
    tests.add(test("half-texel native crop survives all four native rotations", CameraViewTransformTest::halfTexel));
    tests.add(test("FILL occupies the complete output quad", CameraViewTransformTest::fillQuad));
    tests.add(test("FIT keeps the complete source and centers its letterbox", CameraViewTransformTest::fitWholeSource));
    tests.add(test("input and independent results never alias mutable matrices", CameraViewTransformTest::immutability));
    tests.add(test("manual turns normalize negative and very large integer values", CameraViewTransformTest::manualNormalization));
    tests.add(test("null or non-16-element matrices are rejected", CameraViewTransformTest::invalidLength));
    tests.add(test("NaN and infinity are rejected in every matrix component", CameraViewTransformTest::invalidFinite));
    tests.add(test("nonpositive capture and output dimensions are rejected", CameraViewTransformTest::invalidDimensions));
    tests.add(test("unsupported display angles are rejected", CameraViewTransformTest::invalidDisplay));
    tests.add(test("zero and collinear pixel axes are rejected", CameraViewTransformTest::invalidAxes));
    tests.add(test("in-bounds shear cannot masquerade as no-stretch geometry", CameraViewTransformTest::invalidShear));
    tests.add(test("perspective and out-of-plane matrices are rejected", CameraViewTransformTest::invalidAffine));
    tests.add(test("native coordinates outside the source are rejected", CameraViewTransformTest::invalidBounds));
    int failures = 0;
    for (NamedCase test : tests) {
      try {
        test.body.run();
        System.out.println("PASS " + test.name);
      } catch (Throwable failure) {
        failures++;
        System.err.println("FAIL " + test.name + ": " + failure);
        failure.printStackTrace(System.err);
      }
    }
    System.out.println("CameraViewTransformTest: " + (tests.size() - failures) + "/" + tests.size()
        + " passed; " + failures + " failed; " + scenarios + " complete geometry scenarios");
    if (failures != 0) System.exit(1);
  }

  private static void family(int nativeTurn, int displayTurn) {
    int[][] sizes = {{640, 480, 266, 150}, {1280, 720, 266, 150}, {480, 640, 150, 266}};
    for (int[] size : sizes) for (int variant = 0; variant < 8; variant++) {
      NativeMap raw = nativeMap(nativeTurn, variant, size[0], size[1]);
      for (int manual = 0; manual < 4; manual++) for (boolean fill : new boolean[] {true, false}) {
        verify(raw, size[0], size[1], size[2], size[3], displayTurn * 90, manual, fill);
      }
    }
  }

  private static void verify(NativeMap raw, int cw, int ch, int ow, int oh,
      int display, int manual, boolean fill) {
    float[] input = raw.matrix();
    float[] before = input.clone();
    CameraViewTransform.Result result = CameraViewTransform.calculate(input, cw, ch, ow, oh,
        display, manual, fill);
    check(Arrays.equals(input, before), "calculation must not modify native SurfaceTexture matrix");
    int additional = mod(manual - display / 90);
    Point corner = raw.at(rotate(new Point(0, 0), additional));
    Point alongX = raw.at(rotate(new Point(1, 0), additional));
    Point alongY = raw.at(rotate(new Point(0, 1), additional));
    double width = distance(corner, alongX, cw, ch);
    double height = distance(corner, alongY, cw, ch);
    double aspect = width / height;
    double destination = (double) ow / oh;
    double cropX = fill && aspect > destination ? destination / aspect : 1;
    double cropY = fill && aspect < destination ? aspect / destination : 1;
    double scaleX = !fill && aspect < destination ? aspect / destination : 1;
    double scaleY = !fill && aspect > destination ? destination / aspect : 1;
    near(aspect, result.effectiveSourceAspect, EPS, "aspect uses transformed SOURCE PIXELS");
    near(cropX, result.cropFractionX, EPS, "horizontal crop fraction");
    near(cropY, result.cropFractionY, EPS, "vertical crop fraction");
    near(scaleX, result.vertexScaleX, EPS, "horizontal FIT extent");
    near(scaleY, result.vertexScaleY, EPS, "vertical FIT extent");
    check(result.surfaceAxesSwapped == (raw.turn % 2 != 0), "raw native axis swap remains independent of view options");
    expectedVertex(result.vertexMatrix, scaleX, scaleY);

    double[][] probes = {{0, 0}, {1, 0}, {0, 1}, {1, 1}, {.5, .5}, {.13, .71}, {.79, .23}, {.37, .19}};
    for (double[] probe : probes) {
      Point cropped = new Point(.5 + (probe[0] - .5) * cropX, .5 + (probe[1] - .5) * cropY);
      Point expected = raw.at(rotate(cropped, additional));
      Point actual = map(result.textureMatrix, probe[0], probe[1]);
      nearPoint(expected, actual, "asymmetric marker sampling retains native flip/crop exactly once");
      check(actual.x >= raw.left - EPS && actual.x <= raw.right + EPS
          && actual.y >= raw.bottom - EPS && actual.y <= raw.top + EPS,
          "every sampled point stays inside the native crop, including half-texel margins");
    }
    nearPoint(raw.at(new Point(.5, .5)), map(result.textureMatrix, .5, .5),
        "center is preserved even when native crop is not centered at UV .5");
    if (!fill) assertWholeRegion(result.textureMatrix, raw);
    double det = result.textureMatrix[0] * (double) result.textureMatrix[5]
        - result.textureMatrix[1] * (double) result.textureMatrix[4];
    double rawDet = input[0] * (double) input[5] - input[1] * (double) input[4];
    check(Math.signum(det) == Math.signum(rawDet), "view options cannot erase native reflection handedness");
    assertPixelCircle(result, cw, ch, ow, oh);
    scenarios++;
  }

  /** Invert the resulting sample map and draw a physical SOURCE-PIXEL circle into output pixels. */
  private static void assertPixelCircle(CameraViewTransform.Result result, int cw, int ch, int ow, int oh) {
    Point sourceCenter = map(result.textureMatrix, .5, .5);
    double sourcePixelsX = Math.hypot(result.textureMatrix[0] * (double) cw,
        result.textureMatrix[1] * (double) ch);
    double sourcePixelsY = Math.hypot(result.textureMatrix[4] * (double) cw,
        result.textureMatrix[5] * (double) ch);
    double radius = .07 * Math.min(sourcePixelsX, sourcePixelsY);
    double firstRadius = -1;
    for (int marker = 0; marker < 16; marker++) {
      double angle = 2 * Math.PI * marker / 16;
      Point source = new Point(sourceCenter.x + radius * Math.cos(angle) / cw,
          sourceCenter.y + radius * Math.sin(angle) / ch);
      Point outputUv = inverse(result.textureMatrix, source);
      Point outputClip = map(result.vertexMatrix, outputUv.x * 2 - 1, outputUv.y * 2 - 1);
      double outputRadius = Math.hypot(outputClip.x * ow / 2, outputClip.y * oh / 2);
      if (firstRadius < 0) firstRadius = outputRadius;
      near(firstRadius, outputRadius, .00015, "source-pixel circle remains a circle, never a tall narrow ellipse");
    }
  }

  private static void knownRatios() {
    float[] identity = identity();
    CameraViewTransform.Result fill = CameraViewTransform.calculate(identity, 640, 480, 266, 150, 0, 0, true);
    near(4.0 / 3, fill.effectiveSourceAspect, EPS, "landscape source aspect");
    near(100.0 / 133, fill.cropFractionY, EPS, "landscape FILL crops height");
    CameraViewTransform.Result fit = CameraViewTransform.calculate(identity, 640, 480, 266, 150, 0, 0, false);
    near(100.0 / 133, fit.vertexScaleX, EPS, "landscape FIT centers narrower whole view");
    float[] quarter = nativeMap(1, 0, 640, 480).matrix();
    CameraViewTransform.Result portrait = CameraViewTransform.calculate(quarter, 640, 480, 266, 150, 0, 0, true);
    near(.75, portrait.effectiveSourceAspect, EPS, "native quarter turn swaps source-pixel axes");
    near(225.0 / 532, portrait.cropFractionY, EPS, "portrait FILL uses broad centered crop");
    expectedVertex(portrait.vertexMatrix, 1, 1);
  }

  private static void clockwiseMarkers() {
    CameraViewTransform.Result result = CameraViewTransform.calculate(identity(), 640, 640, 400, 400, 0, 1, true);
    for (Point marker : markers()) {
      Point output = inverse(result.textureMatrix, marker);
      nearPoint(new Point(marker.y, 1 - marker.x), output,
          "manual +90 IMAGE moves upper-left content toward upper-right");
    }
  }

  private static void displayMarkers() {
    CameraViewTransform.Result result = CameraViewTransform.calculate(identity(), 640, 640, 400, 400, 90, 0, true);
    for (Point marker : markers()) {
      nearPoint(new Point(1 - marker.y, marker.x), inverse(result.textureMatrix, marker),
          "display +90 cancels the phone's clockwise turn");
    }
  }

  private static Point[] markers() {
    return new Point[] {new Point(.2, .8), new Point(.7, .85), new Point(.82, .27), new Point(.31, .14)};
  }

  private static void cancelTurns() {
    NativeMap raw = nativeMap(1, 5, 640, 480);
    for (int turn = 0; turn < 4; turn++) {
      CameraViewTransform.Result base = CameraViewTransform.calculate(raw.matrix(), 640, 480, 266, 150, 0, 0, true);
      CameraViewTransform.Result canceled = CameraViewTransform.calculate(raw.matrix(), 640, 480, 266, 150, turn * 90, turn, true);
      nearMatrix(base.textureMatrix, canceled.textureMatrix, "equal manual/display rotations cancel");
      nearMatrix(base.vertexMatrix, canceled.vertexMatrix, "cancellation cannot change crop aspect");
    }
  }

  private static void nativeAppliedOnce() {
    for (int turn = 0; turn < 4; turn++) {
      float[] nativeMatrix = nativeMap(turn, 0, 640, 640).matrix();
      CameraViewTransform.Result result = CameraViewTransform.calculate(nativeMatrix, 640, 640, 400, 400, 0, 0, true);
      nearMatrix(nativeMatrix, result.textureMatrix, "native rotation is not replayed as an extra rotation");
      expectedVertex(result.vertexMatrix, 1, 1);
    }
  }

  private static void rawAxisSwap() {
    for (int raw = 0; raw < 4; raw++) for (int display = 0; display < 4; display++) for (int manual = 0; manual < 4; manual++) {
      CameraViewTransform.Result result = CameraViewTransform.calculate(nativeMap(raw, 3, 640, 480).matrix(),
          640, 480, 266, 150, display * 90, manual, true);
      check(result.surfaceAxesSwapped == (raw % 2 != 0), "diagnostic flag describes native axes only");
    }
  }

  private static void offCenter() { verify(nativeMap(3, 4, 640, 480), 640, 480, 266, 150, 90, 3, true); }

  private static void halfTexel() {
    for (int raw = 0; raw < 4; raw++) for (boolean fill : new boolean[] {true, false}) {
      verify(nativeMap(raw, 7, 1920, 1080), 1920, 1080, 266, 150, 270, 2, fill);
    }
  }

  private static void fillQuad() {
    for (int raw : new int[] {0, 1}) {
      CameraViewTransform.Result result = CameraViewTransform.calculate(nativeMap(raw, 4, 640, 480).matrix(),
          640, 480, 266, 150, 0, 0, true);
      expectedVertex(result.vertexMatrix, 1, 1);
      check(result.cropFractionX == 1 || result.cropFractionY == 1, "FILL crops one axis only");
    }
  }

  private static void fitWholeSource() {
    NativeMap raw = nativeMap(1, 5, 640, 480);
    CameraViewTransform.Result result = CameraViewTransform.calculate(raw.matrix(), 640, 480, 266, 150, 0, 0, false);
    near(1, result.cropFractionX, EPS, "FIT does not crop x");
    near(1, result.cropFractionY, EPS, "FIT does not crop y");
    assertWholeRegion(result.textureMatrix, raw);
    check(result.vertexScaleX == 1 || result.vertexScaleY == 1, "FIT letterboxes one axis only");
    nearPoint(new Point(0, 0), map(result.vertexMatrix, 0, 0), "letterbox is centered in clip space");
  }

  private static void immutability() {
    float[] input = nativeMap(1, 7, 640, 480).matrix();
    float[] original = input.clone();
    CameraViewTransform.Result first = CameraViewTransform.calculate(input, 640, 480, 266, 150, 90, 3, true);
    CameraViewTransform.Result second = CameraViewTransform.calculate(input, 640, 480, 266, 150, 90, 3, true);
    check(first.textureMatrix != input && second.textureMatrix != input, "texture results cannot alias native input");
    check(first.textureMatrix != second.textureMatrix && first.vertexMatrix != second.vertexMatrix,
        "results cannot share mutable global scratch matrices");
    float[] expected = second.textureMatrix.clone();
    first.textureMatrix[0] = 42;
    first.vertexMatrix[0] = 42;
    check(Arrays.equals(input, original) && Arrays.equals(second.textureMatrix, expected),
        "mutating one result cannot alter input or another result");
  }

  private static void manualNormalization() {
    for (int manual : new int[] {Integer.MIN_VALUE, -1001, -5, -1, 4, 5, 1001, Integer.MAX_VALUE}) {
      float[] raw = nativeMap(3, 4, 640, 480).matrix();
      CameraViewTransform.Result expected = CameraViewTransform.calculate(raw, 640, 480, 266, 150, 270, mod(manual), true);
      CameraViewTransform.Result actual = CameraViewTransform.calculate(raw, 640, 480, 266, 150, 270, manual, true);
      nearMatrix(expected.textureMatrix, actual.textureMatrix, "normalization occurs before multiplying degrees");
    }
  }

  private static void invalidLength() {
    invalid(null, 640, 480, 266, 150, 0);
    for (int length : new int[] {0, 1, 15, 17, 32}) invalid(new float[length], 640, 480, 266, 150, 0);
  }

  private static void invalidFinite() {
    for (int index = 0; index < 16; index++) for (float value : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
      float[] matrix = identity(); matrix[index] = value;
      invalid(matrix, 640, 480, 266, 150, 0);
    }
  }

  private static void invalidDimensions() {
    for (int dimension = 0; dimension < 4; dimension++) for (int value : new int[] {0, -1, Integer.MIN_VALUE}) {
      int[] dims = {640, 480, 266, 150}; dims[dimension] = value;
      invalid(identity(), dims[0], dims[1], dims[2], dims[3], 0);
    }
  }

  private static void invalidDisplay() {
    for (int degrees : new int[] {-90, 1, 45, 89, 91, 360, 450, Integer.MAX_VALUE}) {
      invalid(identity(), 640, 480, 266, 150, degrees);
    }
  }

  private static void invalidAxes() {
    float[] zero = identity(); zero[0] = 0;
    invalid(zero, 640, 480, 266, 150, 0);
    float[] line = identity(); line[0] = .3f; line[1] = .2f; line[4] = .3f; line[5] = .2f;
    invalid(line, 640, 480, 266, 150, 0);
  }

  private static void invalidShear() {
    float[] matrix = identity(); matrix[0] = .5f; matrix[4] = .1f; matrix[5] = .5f;
    matrix[12] = .1f; matrix[13] = .1f;
    invalid(matrix, 640, 480, 266, 150, 0);
  }

  private static void invalidAffine() {
    for (int index : new int[] {2, 3, 6, 7, 8, 9, 11, 14}) {
      float[] matrix = identity(); matrix[index] = .1f;
      invalid(matrix, 640, 480, 266, 150, 0);
    }
    for (int index : new int[] {10, 15}) {
      float[] matrix = identity(); matrix[index] = 0;
      invalid(matrix, 640, 480, 266, 150, 0);
    }
  }

  private static void invalidBounds() {
    for (float displacement : new float[] {-.02f, .02f}) {
      float[] x = identity(); x[12] = displacement;
      invalid(x, 640, 480, 266, 150, 0);
      float[] y = identity(); y[13] = displacement;
      invalid(y, 640, 480, 266, 150, 0);
    }
  }

  private static void invalid(float[] matrix, int cw, int ch, int ow, int oh, int display) {
    try { CameraViewTransform.calculate(matrix, cw, ch, ow, oh, display, 0, true); }
    catch (IllegalArgumentException expected) {
      check("CAMERA_VIEW_INVALID".equals(expected.getMessage()), "invalid geometry must use a static safe code");
      return;
    }
    throw new AssertionError("Invalid geometry was accepted");
  }

  private static NativeMap nativeMap(int turn, int variant, int width, int height) {
    boolean flipX = variant == 1 || variant == 3 || variant == 5;
    boolean flipY = variant == 2 || variant == 3 || variant == 7;
    if (variant == 4 || variant == 5) return new NativeMap(turn, flipX, flipY, .07, .21, .81, .89);
    if (variant >= 6) return new NativeMap(turn, flipX, flipY, .5 / width, .5 / height, 1 - .5 / width, 1 - .5 / height);
    return new NativeMap(turn, flipX, flipY, 0, 0, 1, 1);
  }

  private static final class NativeMap {
    final int turn;
    final boolean flipX, flipY;
    final double left, bottom, right, top;
    NativeMap(int turn, boolean flipX, boolean flipY, double left, double bottom, double right, double top) {
      this.turn = turn; this.flipX = flipX; this.flipY = flipY;
      this.left = left; this.bottom = bottom; this.right = right; this.top = top;
    }
    Point at(Point point) {
      Point rotated = rotate(point, turn);
      double x = flipX ? 1 - rotated.x : rotated.x;
      double y = flipY ? 1 - rotated.y : rotated.y;
      return new Point(left + x * (right - left), bottom + y * (top - bottom));
    }
    float[] matrix() {
      Point origin = at(new Point(0, 0)), x = at(new Point(1, 0)), y = at(new Point(0, 1));
      float[] result = identity();
      result[0] = (float) (x.x - origin.x); result[1] = (float) (x.y - origin.y);
      result[4] = (float) (y.x - origin.x); result[5] = (float) (y.y - origin.y);
      result[12] = (float) origin.x; result[13] = (float) origin.y;
      return result;
    }
  }

  private static Point rotate(Point point, int turns) {
    switch (mod(turns)) {
      case 1: return new Point(1 - point.y, point.x);
      case 2: return new Point(1 - point.x, 1 - point.y);
      case 3: return new Point(point.y, 1 - point.x);
      default: return point;
    }
  }
  private static int mod(int value) { return (value % 4 + 4) % 4; }
  private static Point map(float[] m, double x, double y) {
    return new Point(m[0] * x + m[4] * y + m[12], m[1] * x + m[5] * y + m[13]);
  }
  private static Point inverse(float[] matrix, Point point) {
    double x = point.x - matrix[12], y = point.y - matrix[13];
    double determinant = matrix[0] * (double) matrix[5] - matrix[1] * (double) matrix[4];
    check(Math.abs(determinant) > 1e-15, "invertible visible sample map");
    return new Point((matrix[5] * x - matrix[4] * y) / determinant,
        (-matrix[1] * x + matrix[0] * y) / determinant);
  }
  private static double distance(Point a, Point b, int width, int height) {
    return Math.hypot((b.x - a.x) * width, (b.y - a.y) * height);
  }
  private static float[] identity() {
    float[] result = new float[16]; result[0] = result[5] = result[10] = result[15] = 1;
    return result;
  }
  private static void expectedVertex(float[] actual, double x, double y) {
    float[] expected = identity(); expected[0] = (float) x; expected[5] = (float) y;
    nearMatrix(expected, actual, "vertex mapping has centered scale only, never a second camera rotation");
    for (int sx : new int[] {-1, 1}) for (int sy : new int[] {-1, 1}) {
      Point corner = map(actual, sx, sy);
      check(Math.abs(corner.x) <= 1 + EPS && Math.abs(corner.y) <= 1 + EPS, "vertex bounds remain within output");
    }
  }
  private static void assertWholeRegion(float[] matrix, NativeMap raw) {
    double minX = 1, minY = 1, maxX = 0, maxY = 0;
    for (int x = 0; x <= 1; x++) for (int y = 0; y <= 1; y++) {
      Point point = map(matrix, x, y);
      minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x);
      minY = Math.min(minY, point.y); maxY = Math.max(maxY, point.y);
    }
    near(raw.left, minX, EPS, "FIT retains native left edge");
    near(raw.right, maxX, EPS, "FIT retains native right edge");
    near(raw.bottom, minY, EPS, "FIT retains native bottom edge");
    near(raw.top, maxY, EPS, "FIT retains native top edge");
  }
  private static void nearPoint(Point expected, Point actual, String message) {
    near(expected.x, actual.x, EPS, message + " x"); near(expected.y, actual.y, EPS, message + " y");
  }
  private static void nearMatrix(float[] expected, float[] actual, String message) {
    check(actual != null && actual.length == 16, "16-element matrix");
    for (int i = 0; i < 16; i++) near(expected[i], actual[i], EPS, message + " at " + i);
  }
  private static void near(double expected, double actual, double tolerance, String message) {
    check(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance,
        message + ": expected " + expected + ", got " + actual);
  }
  private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
  private static NamedCase test(String name, Runnable body) { return new NamedCase(name, body); }
  private static final class NamedCase {
    final String name; final Runnable body;
    NamedCase(String name, Runnable body) { this.name = name; this.body = body; }
  }
  private static final class Point {
    final double x, y;
    Point(double x, double y) { this.x = x; this.y = y; }
  }
}
