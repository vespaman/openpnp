import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.openpnp.machine.reference.vision.DisplacementCheck;

/**
 * The arithmetic of the camera displacement check. It is the part that can be checked without a
 * machine: the observations are the offsets a camera reports for a tip, and they are modelled as
 *
 *   X(k,j) = u_k - e_j
 *
 * with u the nozzle error and e the camera error. Only differences of the e's can ever be seen, so
 * the gauge sum(e) = 0 is used, which is also what the write-back relies on.
 */
public class DisplacementSolveTest {

    static double[] observations(int n, double[][] u, double[][] e, int axis) {
        double[] y = new double[n*n];
        int r = 0;
        for (int k = 0; k < n; k++) {
            for (int j = 0; j < n; j++) {
                y[r++] = u[k][axis] - e[j][axis];
            }
        }
        return y;
    }

    static int[] nozzleIndices(int n) {
        int[] nk = new int[n*n];
        int r = 0;
        for (int k = 0; k < n; k++) {
            for (int j = 0; j < n; j++) {
                nk[r++] = k;
            }
        }
        return nk;
    }

    static int[] cameraIndices(int n) {
        int[] cj = new int[n*n];
        int r = 0;
        for (int k = 0; k < n; k++) {
            for (int j = 0; j < n; j++) {
                cj[r++] = j;
            }
        }
        return cj;
    }

    @Test
    public void recoversTwoCameraDisplacement() {
        int n = 2;
        double[][] u = {
                {0.013, -0.021},
                {-0.037, 0.044},
        };
        // gauge sum(e) = 0 as the write-back needs it
        double[][] e = {
                {0.298, -0.183},
                {-0.298, 0.183},
        };
        for (int axis = 0; axis < 2; axis++) {
            double[] x = DisplacementCheck.leastSquares(n, nozzleIndices(n), cameraIndices(n),
                    observations(n, u, e, axis));
            assertNotNull(x);
            for (int k = 0; k < n; k++) {
                assertEquals(u[k][axis], x[k], 1e-12, "nozzle "+k);
            }
            for (int j = 0; j < n; j++) {
                assertEquals(e[j][axis], DisplacementCheck.predictE(n, x, j), 1e-12, "camera "+j);
            }
            assertEquals(0., DisplacementCheck.sum(
                    new double[] {DisplacementCheck.predictE(n, x, 0),
                            DisplacementCheck.predictE(n, x, 1)}, 0, 1), 1e-12);
        }
    }

    @Test
    public void recoversThreeCameraDisplacement() {
        int n = 3;
        double[][] u = {
                {0.01, 0.02},
                {-0.03, 0.04},
                {0.05, -0.06},
        };
        double[][] e = {
                {0.21, -0.11},
                {-0.07, 0.16},
                {-0.14, -0.05},
        };
        for (int axis = 0; axis < 2; axis++) {
            double[] x = DisplacementCheck.leastSquares(n, nozzleIndices(n), cameraIndices(n),
                    observations(n, u, e, axis));
            assertNotNull(x);
            for (int k = 0; k < n; k++) {
                assertEquals(u[k][axis], x[k], 1e-12, "nozzle "+k);
            }
            double gauge = 0.;
            for (int j = 0; j < n; j++) {
                assertEquals(e[j][axis], DisplacementCheck.predictE(n, x, j), 1e-12, "camera "+j);
                gauge += DisplacementCheck.predictE(n, x, j);
            }
            assertEquals(0., gauge, 1e-12);
        }
    }

    /**
     * The point of the crossed measurements: with only the paired views every camera can simply be
     * blamed for its own tip's offset and nothing is learned about the cameras. Two unknowns per
     * pair, one observation per pair.
     */
    @Test
    public void pairedShotsAloneCannotBeSolved() {
        int n = 2;
        int[] nk = {0, 1};
        int[] cj = {0, 1};
        double[] y = {0.281, -0.059};
        assertNull(DisplacementCheck.leastSquares(n, nk, cj, y));
    }

    /**
     * The same with one crossed view added: the displacement difference becomes visible, but the
     * common part of it is still a free choice, which the gauge resolves by putting it nowhere.
     */
    @Test
    public void oneCrossedViewRevealsTheDifference() {
        int n = 2;
        int[] nk = {0, 1, 0};
        int[] cj = {0, 1, 1};
        double[] y = {0.281, -0.059, 0.341};
        // 3 observations, 3 unknowns: exactly determined
        double[] x = DisplacementCheck.leastSquares(n, nk, cj, y);
        assertNotNull(x);
        double difference = DisplacementCheck.predictE(n, x, 0) - DisplacementCheck.predictE(n, x, 1);
        assertEquals(0.06, difference, 1e-9);
    }

    /**
     * A machine whose tips and cameras are rigid must satisfy X(1,1)+X(2,2) = X(1,2)+X(2,1). A
     * violation cannot be fitted away, it can only be reported, and that is what the residual is
     * for: it is the check that the measurement was actually of one rigid machine.
     */
    @Test
    public void violationOfRigidityRemainsAsResidual() {
        int n = 2;
        double[] y = observations(n, new double[][] {{0., 0.}, {0., 0.}},
                new double[][] {{0.3, 0.}, {-0.3, 0.}}, 0);
        double consistent = residual(n, y);
        y[3] += 0.1;
        double violated = residual(n, y);
        assertEquals(0., consistent, 1e-12);
        assertTrue(violated > 0.04, "residual "+violated);
    }

    static double residual(int n, double[] y) {
        int[] nk = nozzleIndices(n);
        int[] cj = cameraIndices(n);
        double[] x = DisplacementCheck.leastSquares(n, nk, cj, y);
        assertNotNull(x);
        double square = 0.;
        for (int r = 0; r < y.length; r++) {
            double predicted = DisplacementCheck.predict(n, x, nk[r], cj[r]);
            square += (predicted - y[r])*(predicted - y[r]);
        }
        return Math.sqrt(square/(y.length - (2*n - 1)));
    }

}
