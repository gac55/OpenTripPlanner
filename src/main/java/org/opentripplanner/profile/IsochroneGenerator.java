package org.opentripplanner.profile;

import com.vividsolutions.jts.geom.Coordinate;
import org.apache.commons.math3.util.FastMath;
import org.opentripplanner.analyst.PointSet;
import org.opentripplanner.analyst.core.IsochroneData;
import org.opentripplanner.analyst.request.SampleGridRenderer;
import org.opentripplanner.common.geometry.AccumulativeGridSampler;
import org.opentripplanner.common.geometry.DelaunayIsolineBuilder;
import org.opentripplanner.common.geometry.SparseMatrixZSampleGrid;
import org.opentripplanner.common.geometry.SphericalDistanceLibrary;
import org.opentripplanner.common.geometry.ZSampleGrid;
import org.opentripplanner.analyst.request.SampleGridRenderer.WTWD;

import java.util.ArrayList;
import java.util.List;

import static org.apache.commons.math3.util.FastMath.toRadians;

/**
 * Make a ZSampleGrid from a PointSet and a parallel array of travel times for that PointSet.
 * Those times could come from a ResultSetWithTimes or directly from a PropagatedTimesStore, which has one
 * such array for each of min, avg, and max travel time over the departure time window it represents.
 *
 * If your PointSet is dense enough (e.g. every block in a city) then this should yield a decent surface and isochrones.
 *
 * The reason why we want to do this is that we are now producing travel time stats directly for pointsets as targets
 * of a repeated RAPTOR search, without saving accompanying travel time values for the street vertices along the way.
 */
public abstract class IsochroneGenerator {

    public static final double GRID_SIZE_METERS = 300;

    /**
     * FIXME code duplication, this is ripped off from TimeSurface and should probably replace the version there as it is more generic.
     * @param walkSpeed the walk speed in meters per second
     * @return a grid suitable for making isochrones, based on an arbitrary PointSet and times to reach all those points.
     */
    public static ZSampleGrid makeGrid (PointSet pointSet, int[] times, double walkSpeed) {

        ZSampleGrid grid = null;
        // Off-road max distance MUST be APPROX EQUALS to the grid precision
        // TODO: Loosen this restriction (by adding more closing samples).
        // Change the 0.8 magic factor here with caution.
        final double D0 = 0.8 * GRID_SIZE_METERS; // offroad walk distance roughly grid size
        final double V0 = walkSpeed; // off-road walk speed in m/sec
        Coordinate coordinateOrigin = pointSet.getCoordinate(0); // Use the first feature as the center of the projection
        final double cosLat = FastMath.cos(toRadians(coordinateOrigin.y));
        double dY = Math.toDegrees(GRID_SIZE_METERS / SphericalDistanceLibrary.RADIUS_OF_EARTH_IN_M);
        double dX = dY / cosLat;
        grid = new SparseMatrixZSampleGrid<WTWD>(16, times.length, dX, dY, coordinateOrigin);
        AccumulativeGridSampler.AccumulativeMetric<WTWD> metric =
                new SampleGridRenderer.WTWDAccumulativeMetric(cosLat, D0, V0, GRID_SIZE_METERS);
        AccumulativeGridSampler<WTWD> sampler = new AccumulativeGridSampler<>(grid, metric);

        // Iterate over every point in this PointSet, adding it to the ZSampleGrid
        for (int p = 0; p < times.length; p++) {
            int time = times[p];
            WTWD z = new WTWD();
            z.w = 1.0;
            z.d = 0.0;
            z.wTime = time;
            z.wBoardings = 0; // unused
            z.wWalkDist = 0; // unused
            sampler.addSamplingPoint(pointSet.getCoordinate(p), z, V0);
        }
        sampler.close();

        return grid;
    }


    /**
     * FIXME code duplication: function ripped off from SurfaceResource
     * Make isochrones from a grid. This more general function should probably be reused by SurfaceResource.
     *
     * @param spacingMinutes the number of minutes between isochrones
     * @return a list of evenly-spaced isochrones
     */
    public static List<IsochroneData> getIsochronesAccumulative(ZSampleGrid<WTWD> grid,
                                                          int spacingMinutes, int cutoffMinutes, int nMax) {

        DelaunayIsolineBuilder<WTWD> isolineBuilder = new DelaunayIsolineBuilder<>(
                grid.delaunayTriangulate(), new WTWD.IsolineMetric());

        List<IsochroneData> isochrones = new ArrayList<>();
        for (int minutes = spacingMinutes, n = 0; minutes <= cutoffMinutes && n < nMax; minutes += spacingMinutes, n++) {
            int seconds = minutes * 60;
            SampleGridRenderer.WTWD z0 = new SampleGridRenderer.WTWD();
            z0.w = 1.0;
            z0.wTime = seconds;
            z0.d = GRID_SIZE_METERS;
            IsochroneData isochrone = new IsochroneData(seconds, isolineBuilder.computeIsoline(z0));
            isochrones.add(isochrone);
            if (++n >= nMax) {
                break;
            }
        }

        return isochrones;
    }
}