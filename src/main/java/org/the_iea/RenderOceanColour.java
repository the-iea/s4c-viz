/*******************************************************************************
 * Copyright (c) 2018 The Institute for Environmental Analytics
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the University of Reading, nor the names of the
 *    authors or contributors may be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/

package org.the_iea;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.joda.time.DateTime;

import uk.ac.rdg.resc.edal.dataset.Dataset;
import uk.ac.rdg.resc.edal.dataset.GriddedDataset;
import uk.ac.rdg.resc.edal.dataset.PointDataset;
import uk.ac.rdg.resc.edal.dataset.cdm.ArgoDatasetFactory;
import uk.ac.rdg.resc.edal.dataset.cdm.CdmGridDatasetFactory;
import uk.ac.rdg.resc.edal.domain.Extent;
import uk.ac.rdg.resc.edal.exceptions.EdalException;
import uk.ac.rdg.resc.edal.feature.ProfileFeature;
import uk.ac.rdg.resc.edal.geometry.BoundingBoxImpl;
import uk.ac.rdg.resc.edal.graphics.style.ColouredGlyphLayer;
import uk.ac.rdg.resc.edal.graphics.style.Drawable;
import uk.ac.rdg.resc.edal.graphics.style.MapImage;
import uk.ac.rdg.resc.edal.graphics.style.RasterLayer;
import uk.ac.rdg.resc.edal.graphics.style.ScaleRange;
import uk.ac.rdg.resc.edal.graphics.style.SegmentColourScheme;
import uk.ac.rdg.resc.edal.graphics.utils.PlottingDomainParams;
import uk.ac.rdg.resc.edal.graphics.utils.SimpleFeatureCatalogue;
import uk.ac.rdg.resc.edal.grid.TimeAxis;
import uk.ac.rdg.resc.edal.metadata.GridVariableMetadata;
import uk.ac.rdg.resc.edal.util.Extents;
import uk.ac.rdg.resc.edal.util.TimeUtils;

/**
 * Renders frames for a visualisation of ocean colour with argo float positions
 * superimposed
 * 
 * This requires 2 datasets:
 * 
 * * The CCI ocean colour dataset for the desired variable (e.g. chlor_a) - see
 * http://esa-oceancolour-cci.org/?q=node/169 * The Argo profile dataset
 * (available from ftp.ifremer.fr)
 *
 * It's essentially a one-shot application to generate the required PNG frames
 * in the equirectangular projection, so doesn't do anything sensible like take
 * variable arguments etc. You need to edit the code if you want a different
 * result.
 * 
 * @author Guy Griffiths
 */
public class RenderOceanColour {
    public static void main(String[] args) throws EdalException, IOException {
        /*
         * Data paths
         */
        String gridDataPath = "/home/guy/Data/s4c/cci-oc/*.nc";
        /*
         * Variable ID to plot
         */
        String gridVar = "chlor_a";
        String profDataPath = "/home/guy/Data/s4c/cci-oc/profiles/**/**/**/*.nc";
        /*
         * We only want to plot locations, but we need a variable to plot anyway
         */
        String pointVar = "TEMP_ADJUSTED";
        /*
         * Output directory
         */
        String outDir = "/home/guy/Data/s4c/output-cci-oc/";
        File outPathFile = new File(outDir);
        if (!outPathFile.exists()) {
            outPathFile.mkdirs();
        }

        /*
         * Background image. Output takes its size from this image. Needs to be
         * in equirectangular projection.
         * 
         * 4000x2000 is the recommended resolution for the pufferfish globe
         */
        BufferedImage background = ImageIO
                .read(GOSATGriddedDataset.class.getResource("/blue_marble-4000x2000.png"));

        int width = background.getWidth();
        int height = background.getHeight();

        /*
         * Create grid dataset for ocean colour data
         */
        CdmGridDatasetFactory factory = new CdmGridDatasetFactory();
        GriddedDataset gridDataset = (GriddedDataset) factory.createDataset("oceancolour",
                gridDataPath);

        /*
         * And point dataset for argo floats
         */
        ArgoDatasetFactory profFactory = new ArgoDatasetFactory();
        @SuppressWarnings("unchecked")
        PointDataset<ProfileFeature> profDataset = (PointDataset<ProfileFeature>) profFactory
                .createDataset("argos", profDataPath);

        /*
         * Create simple data catalogue to access both gridded and point
         * datasets
         */
        List<Dataset> datasets = new ArrayList<>();
        datasets.add(gridDataset);
        datasets.add(profDataset);
        SimpleFeatureCatalogue<Dataset> catalogue = new SimpleFeatureCatalogue<Dataset>(datasets,
                false);

        /*
         * Colour scale range for chlorophyll concentration
         */
        Extent<Float> range = Extents.newExtent(0.001f, 5f);
//        System.out.println(GraphicsUtils.estimateValueRange(gridDataset, gridVar));

        /*
         * Write an image for each palette to disk. These can be used to
         * colourise grey images later
         */
//        for (String pal : ColourPalette.getPredefinedPalettes()) {
//            SegmentColourScheme colourScheme = new SegmentColourScheme(
//                    new ScaleRange(0f, 100f, false), null, null, new Color(0, true), pal, 250);
//            BufferedImage scaleBar = colourScheme.getScaleBar(10, 250, 0, true, false, null, null);
//            ImageIO.write(scaleBar, "png", new File(outDir + "/palette-" + pal + ".png"));
//        }

        /*-
         * Define the makeup of the image to draw.  It consists of:
         * 
         * * A raster layer using a logarithmic scale with the default palette
         * * A coloured glyph layer, which uses an all-white scale
         */
        MapImage mapImage = new MapImage();
        Drawable rasterLayer = new RasterLayer(gridVar, new SegmentColourScheme(
                new ScaleRange(range, true), null, null, new Color(0, true), "default", 250));
        Drawable pointLayer = new ColouredGlyphLayer(pointVar, "circle", new SegmentColourScheme(
                new ScaleRange(0f, 10f, false), null, null, new Color(0, true), "#ffffff", 1));
        mapImage.getLayers().add(rasterLayer);
        mapImage.getLayers().add(pointLayer);

        /*
         * Get the time axis for the gridded data
         */
        GridVariableMetadata metadata = gridDataset.getVariableMetadata(gridVar);
        TimeAxis timeAxis = metadata.getTemporalDomain();

        DateTime currentTime = timeAxis.getCoordinateValue(0);
        long deltaT = 1000L * 60 * 60 * 24;
        /*
         * This currently is equivalent to looping through the time axis.
         * However, we may want a different deltaT, since in principal Argo
         * floats are not on a regular time axis.
         */
        while (currentTime.isBefore(timeAxis.getCoordinateBounds(timeAxis.size() - 1).getHigh())) {
            DateTime time = timeAxis.getCoordinateValue(timeAxis.findIndexOf(currentTime));
            /*
             * Use a big time range. The argo profile files only contain
             * profiles which have been updated.
             * 
             * By using a large time range, we get all of the individual floats.
             * Because the target time is set, we will select the feature for
             * the float which is closest to the target time.
             */
            Extent<DateTime> timeRange = Extents.newExtent(currentTime.minusDays(7),
                    currentTime.plusDays(7));
            PlottingDomainParams params = new PlottingDomainParams(width, height,
                    BoundingBoxImpl.global(), null, timeRange, null, null, time);
            /*
             * Render the image of the data
             */
            BufferedImage dataImage = mapImage.drawImage(params, catalogue);
            currentTime = currentTime.plus(deltaT);

            BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = output.createGraphics();
            g.drawImage(background, 0, 0, null);
            g.drawImage(dataImage, 0, 0, null);
            /*
             * Write frames with datetime. This allows for easier recreation of
             * individual frames, and we can use the "-pattern_type glob"
             * argument with ffmpeg to generate the video
             */
            ImageIO.write(output, "png", new File(
                    outDir + "/frame-" + TimeUtils.dateTimeToISO8601(currentTime) + ".png"));
        }

        System.out
                .println("Finished writing frames.  Now run:\nffmpeg -r 25 -pattern_type glob -i '"
                        + outDir + "*.png' -crf 18 -c:v libx264 -pix_fmt yuv420p output.mp4");
    }
}
