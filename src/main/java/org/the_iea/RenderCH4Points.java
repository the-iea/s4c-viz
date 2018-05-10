/*******************************************************************************
 * Copyright (c) 2018 The University of Reading
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
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.commons.collections4.queue.CircularFifoQueue;

import ucar.ma2.Array;
import ucar.nc2.Variable;
import ucar.nc2.dataset.NetcdfDataset;
import uk.ac.rdg.resc.edal.dataset.cdm.NetcdfDatasetAggregator;
import uk.ac.rdg.resc.edal.exceptions.EdalException;
import uk.ac.rdg.resc.edal.graphics.style.ColourScheme;
import uk.ac.rdg.resc.edal.graphics.style.ScaleRange;
import uk.ac.rdg.resc.edal.graphics.style.SegmentColourScheme;
import uk.ac.rdg.resc.edal.grid.RegularGrid;
import uk.ac.rdg.resc.edal.grid.RegularGridImpl;
import uk.ac.rdg.resc.edal.position.HorizontalPosition;
import uk.ac.rdg.resc.edal.util.GISUtils;
import uk.ac.rdg.resc.edal.util.GridCoordinates2D;
import uk.ac.rdg.resc.edal.util.cdm.CdmUtils;

public class RenderCH4Points {
    private static class DataPoint {
        final float lat;
        final float lon;
        final float value;

        public DataPoint(float lat, float lon, float value) {
            super();
            this.lat = lat;
            this.lon = lon;
            this.value = value;
        }
    }

    private static final int FADE_SIZE = 10;
    private static Font LABEL_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 40);

    public static void main(String[] args) throws EdalException, IOException {
        /*
         * The output path for image files
         */
        String outPath = "/home/guy/Data/s4c/output/";

        BufferedImage background = ImageIO
                .read(RenderCH4Points.class.getResource("/blue_marble-2048x1024.png"));

        RegularGrid grid = new RegularGridImpl(-180, -90, 180, 90, GISUtils.defaultGeographicCRS(),
                background.getWidth(), background.getHeight());
        /*
         * Create griddedData circular queue containing arrays of DataPoints.
         * 
         * On each timestep we plot the entire contents of the queue, fading out
         * the oldest points.
         */
        CircularFifoQueue<List<DataPoint>> pointsQ = new CircularFifoQueue<>(FADE_SIZE);

        /*
         * Get list of all of the files to render
         */
        List<File> files = CdmUtils.expandGlobExpression("/home/guy/Data/s4c/ch4/**/*.nc");
        /*
         * Sort them alphabetically, which will correspond to time order
         */
        Collections.sort(files);

        /*
         * The actual maximum is around 11500, but only 2 files contain anything
         * like this high. 5000 is griddedData more realistic limit (and we saturate above
         * that anyway)
         */
        SegmentColourScheme colourScheme = new SegmentColourScheme(
                new ScaleRange(1750f, 1900f, false), null, null, new Color(0, true), "psu-plasma",
                250);
        DecimalFormat frameNoFormat = new DecimalFormat("0000");
        int frameNo = 0;
        Pattern fnPattern = Pattern.compile("ESACCI-GHG-L2-CH4-GOSAT-OCPR-(.*)-fv7.2.nc");
        for (File file : files) {
            try (NetcdfDataset dataset = NetcdfDatasetAggregator
                    .getDataset(file.getAbsolutePath())) {
                Variable latVar = dataset.findVariable("latitude");
                Variable lonVar = dataset.findVariable("longitude");
                Variable ch4QVar = dataset.findVariable("xch4_quality_flag");
                Variable ch4Var = dataset.findVariable("xch4");

                Array latVals = latVar.read();
                Array lonVals = lonVar.read();
                Array ch4QVals = ch4QVar.read();
                Array ch4Vals = ch4Var.read();
                long n = ch4Vals.getSize();
                List<DataPoint> dataPoints = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    byte qVal = ch4QVals.getByte(i);
                    if (qVal == 0) {
                        dataPoints.add(new DataPoint(latVals.getFloat(i), lonVals.getFloat(i),
                                ch4Vals.getFloat(i)));
                    }
                }
                pointsQ.add(dataPoints);

                Matcher m = fnPattern.matcher(file.getName());
                String dateStr = "";
                if (m.matches()) {
                    dateStr = m.group(1);
                    dateStr = dateStr.substring(0, 4) + "-" + dateStr.substring(4, 6) + "-"
                            + dateStr.substring(6);
                }
                BufferedImage outImage = render(background, grid, pointsQ, colourScheme, dateStr);
                ImageIO.write(outImage, "png",
                        new File(outPath + "frame-" + frameNoFormat.format(frameNo++) + ".png"));
                System.out.println("Written data from " + file.getName());
            }
        }

        System.out.println("Finished writing frames.  Now run:\nffmpeg -r 25 -i '" + outPath
                + "frame-%04d.png' -crf 18 -c:v libx264 -pix_fmt yuv420p output.mp4");
    }

    private static BufferedImage render(BufferedImage background, RegularGrid grid,
            CircularFifoQueue<List<DataPoint>> queue, ColourScheme cs, String dateStr) {
        int width = background.getWidth();
        int height = background.getHeight();
        BufferedImage ret = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = ret.createGraphics();
        g.drawImage(background, 0, 0, width, height, null);

        int stringOffsetX = g.getFontMetrics(LABEL_FONT).stringWidth("0000-11-22") / 2;
        int stringOffsetY = g.getFontMetrics(LABEL_FONT).getHeight();
        
        g.setColor(Color.white);
        g.setFont(LABEL_FONT);
        g.drawString(dateStr, width / 2 - stringOffsetX, height / 5 - stringOffsetY);
        int i = 0;
        int size = 11;
        for (List<DataPoint> points : queue) {
            int opacity = (int) (255 * ((double) ++i / queue.size()));
            for (DataPoint point : points) {
                GridCoordinates2D coords = grid
                        .findIndexOf(new HorizontalPosition(point.lon, point.lat));
                Color c = cs.getColor(point.value);
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), opacity));
                g.fillOval(coords.getX() - size / 2, height - 1 - coords.getY() - size / 2, size, size);
            }
        }

        return ret;
    }
}
