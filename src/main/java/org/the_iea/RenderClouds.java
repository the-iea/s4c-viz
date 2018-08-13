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
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.imageio.ImageIO;

import org.joda.time.DateTime;

import uk.ac.rdg.resc.edal.dataset.GriddedDataset;
import uk.ac.rdg.resc.edal.dataset.cdm.CdmGridDatasetFactory;
import uk.ac.rdg.resc.edal.domain.Extent;
import uk.ac.rdg.resc.edal.exceptions.EdalException;
import uk.ac.rdg.resc.edal.geometry.BoundingBoxImpl;
import uk.ac.rdg.resc.edal.graphics.style.Drawable;
import uk.ac.rdg.resc.edal.graphics.style.MapImage;
import uk.ac.rdg.resc.edal.graphics.style.RasterLayer;
import uk.ac.rdg.resc.edal.graphics.style.ScaleRange;
import uk.ac.rdg.resc.edal.graphics.style.SegmentColourScheme;
import uk.ac.rdg.resc.edal.graphics.utils.ColourPalette;
import uk.ac.rdg.resc.edal.graphics.utils.PlottingDomainParams;
import uk.ac.rdg.resc.edal.graphics.utils.SimpleFeatureCatalogue;
import uk.ac.rdg.resc.edal.grid.TimeAxis;
import uk.ac.rdg.resc.edal.metadata.GridVariableMetadata;
import uk.ac.rdg.resc.edal.util.Extents;
import uk.ac.rdg.resc.edal.util.TimeUtils;

/**
 * Renders frames for visualisation of L3U cloud data.
 * 
 * This renders a set of variables of interest in black-and-white, so that they
 * can be colourised at a later date.
 *
 * @author Guy Griffiths
 */
public class RenderClouds {
    public static void main(String[] args) throws EdalException, IOException {
        /*
         * Data path and main output dir
         */
        String dataPath = "/home/guy/Data/s4c/clouds/*.nc";
        String outDir = "/home/guy/Data/s4c/output-clouds/";

        /*
         * The required size for the pufferfish globe
         */
        int width = 4000;
        int height = 2000;

        /*
         * Create a simple data catalogue
         */
        CdmGridDatasetFactory factory = new CdmGridDatasetFactory();
        GriddedDataset ds = (GriddedDataset) factory.createDataset("clouds", dataPath);
        SimpleFeatureCatalogue<GriddedDataset> catalogue = new SimpleFeatureCatalogue<GriddedDataset>(
                ds, false);

        /*-
         * Define the variables to plot and the scale ranges to use.
         *  
         * The ranges were based on these automatically-determined ranges:
         * 
         * cth_asc,-1.0,21.0
         * ctt_asc,171.3,312.6
         * cer_asc,0.0,100.0
         * cot_asc,0.0,100.0
         * cth_desc,-1.009,21.0
         * ctt_desc,158.5,308.3
         * cer_desc,-4.895,105.0
         * cot_desc,-12.79,268.7
         */
        Map<String, Extent<Float>> var2Range = new HashMap<>();
        var2Range.put("cth_asc", Extents.newExtent(0f, 25f));
        var2Range.put("cth_desc", Extents.newExtent(0f, 25f));
        var2Range.put("ctt_asc", Extents.newExtent(150f, 325f));
        var2Range.put("ctt_desc", Extents.newExtent(150f, 325f));
        var2Range.put("cer_asc", Extents.newExtent(0f, 100f));
        var2Range.put("cer_desc", Extents.newExtent(0f, 100f));
        var2Range.put("cot_asc", Extents.newExtent(0f, 100f));
        var2Range.put("cot_desc", Extents.newExtent(0f, 250f));

        /*
         * Write an image for each palette to disk. These can be used to
         * colourise grey images later
         */
        for (String pal : ColourPalette.getPredefinedPalettes()) {
            SegmentColourScheme colourScheme = new SegmentColourScheme(
                    new ScaleRange(0f, 100f, false), null, null, new Color(0, true), pal, 250);
            BufferedImage scaleBar = colourScheme.getScaleBar(10, 250, 0, true, false, null, null);
            ImageIO.write(scaleBar, "png", new File(outDir + "/palette-" + pal + ".png"));
        }

        /*
         * All variables share the same time axis.
         * 
         * We save the time axis so that we can loop over time in the outer loop
         * and variables in the inner loop.
         * 
         * This is less tidy, but is more efficient
         */
        GridVariableMetadata metadata = ds.getVariableMetadata("cth_asc");
        TimeAxis timeAxis = metadata.getTemporalDomain();

        for (Entry<String, Extent<Float>> entry : var2Range.entrySet()) {
            String var = entry.getKey();
            Extent<Float> range = entry.getValue();

            /*
             * The output path for image files
             */
            String outPath = "/home/guy/Data/s4c/output-clouds/" + var + "/";
            File outPathFile = new File(outPath);
            if (!outPathFile.exists()) {
                outPathFile.mkdirs();
            }

            MapImage mapImage = new MapImage();
            /*-
             * Create using a simple grey palette. This can have a palette
             * applied to it with:
             * 
             * convert grey_image.png palette-<palettename>.png -clut output.png
             * 
             * That way we only need to generate the images once for each
             * variable, and subsequent palette application can be done
             * without having to re-read the original data.
             */
            SegmentColourScheme colourScheme = new SegmentColourScheme(new ScaleRange(range, false),
                    null, null, new Color(0, true), "#ffffff:#000000", 250);
            Drawable rasterLayer = new RasterLayer(var, colourScheme);
            mapImage.getLayers().add(rasterLayer);

            for (DateTime time : timeAxis.getCoordinateValues()) {
                PlottingDomainParams params = new PlottingDomainParams(width, height,
                        BoundingBoxImpl.global(), null, null, null, null, time);
                BufferedImage dataImage = mapImage.drawImage(params, catalogue);

                /*
                 * Write frames with datetime. This allows for easier
                 * recreation, and we can use the "-pattern_type glob" argument
                 * with ffmpeg to generate the video
                 */
                ImageIO.write(dataImage, "png",
                        new File(outPath + "frame-" + TimeUtils.dateTimeToISO8601(time) + ".png"));
            }

        }
        System.out.println(
                "Finished writing frames.  Now run:\nffmpeg -r 25 -pattern_type glob -i '" + outDir
                        + "/<variable>/*.png' -crf 18 -c:v libx264 -pix_fmt yuv420p output.mp4");

        // One-liner to add a background and create movie at the same time.  Need to specify the framerate twice (for each input stream - background and data)
        // ffmpeg -r 25 -loop 1 -i ../../bg.png -r 25 -pattern_type glob -i './*.png' -filter_complex "[0:v][1:v]overlay=shortest=1[v]" -map "[v]" -crf 18 -c:v libx264 -pix_fmt yuv420p output.mp4

        /*-
         * Once the output videos have been generated, you can run the
         * following:
         * 
         * ffmpeg -loop 1 -i bg.png -i output.mp4 -filter_complex "[0:v][1:v]overlay=0:60:shortest=1,format=yuv420p[v]" -map "[v]" -c:a copy -movflags +faststart output-bg.mp4
         * 
         * to add a background image.
         */
    }
}
