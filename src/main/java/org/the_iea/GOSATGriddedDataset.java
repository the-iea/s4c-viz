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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.joda.time.DateTime;
import org.joda.time.chrono.ISOChronology;
import org.the_iea.GOSATGriddedDataset.RegionText;

import ucar.ma2.Array;
import ucar.nc2.Attribute;
import ucar.nc2.Variable;
import ucar.nc2.dataset.NetcdfDataset;
import uk.ac.rdg.resc.edal.dataset.DataReadingStrategy;
import uk.ac.rdg.resc.edal.dataset.GridDataSource;
import uk.ac.rdg.resc.edal.dataset.GriddedDataset;
import uk.ac.rdg.resc.edal.dataset.cdm.NetcdfDatasetAggregator;
import uk.ac.rdg.resc.edal.domain.Extent;
import uk.ac.rdg.resc.edal.exceptions.DataReadingException;
import uk.ac.rdg.resc.edal.exceptions.EdalException;
import uk.ac.rdg.resc.edal.geometry.BoundingBox;
import uk.ac.rdg.resc.edal.geometry.BoundingBoxImpl;
import uk.ac.rdg.resc.edal.graphics.style.MapImage;
import uk.ac.rdg.resc.edal.graphics.style.RasterLayer;
import uk.ac.rdg.resc.edal.graphics.style.ScaleRange;
import uk.ac.rdg.resc.edal.graphics.style.SegmentColourScheme;
import uk.ac.rdg.resc.edal.graphics.utils.PlottingDomainParams;
import uk.ac.rdg.resc.edal.graphics.utils.SimpleFeatureCatalogue;
import uk.ac.rdg.resc.edal.grid.HorizontalGrid;
import uk.ac.rdg.resc.edal.grid.RegularGrid;
import uk.ac.rdg.resc.edal.grid.RegularGridImpl;
import uk.ac.rdg.resc.edal.grid.TimeAxis;
import uk.ac.rdg.resc.edal.grid.TimeAxisImpl;
import uk.ac.rdg.resc.edal.metadata.GridVariableMetadata;
import uk.ac.rdg.resc.edal.metadata.Parameter;
import uk.ac.rdg.resc.edal.position.HorizontalPosition;
import uk.ac.rdg.resc.edal.util.Array4D;
import uk.ac.rdg.resc.edal.util.GridCoordinates2D;
import uk.ac.rdg.resc.edal.util.TimeUtils;
import uk.ac.rdg.resc.edal.util.ValuesArray4D;
import uk.ac.rdg.resc.edal.util.cdm.CdmUtils;

public class GOSATGriddedDataset extends GriddedDataset {
    private static final long serialVersionUID = 1L;

    private int averagingWindow;
    private List<File> files;

    private GridDataSource gds = null;

    public GOSATGriddedDataset(String id, String location, List<String> varsToInclude,
            int averagingWindow, int gridXSize, int gridYSize) {
        super(id, getMetadata(location, varsToInclude, gridXSize, gridYSize, averagingWindow));
        this.averagingWindow = averagingWindow;

        /*
         * Get list of all of the files to render
         */
        this.files = CdmUtils.expandGlobExpression(location);
        /*
         * Sort them alphabetically, which will correspond to time order
         */
        Collections.sort(files);
    }

    private static Collection<GridVariableMetadata> getMetadata(String location,
            List<String> varsToInclude, int gridXSize, int gridYSize, int window) {
        Pattern fnPattern = Pattern.compile(".*(\\d{8}).*");
        /*
         * Opens the first file in the location to see what variables are
         * present
         */
        List<File> files = CdmUtils.expandGlobExpression(location);
        Collections.sort(files);

        /*
         * Create the time axis from the dates in the file names
         */
        List<DateTime> times = new ArrayList<>();
        for (int i = window; i < files.size() - 1 - window; i++) {
            Matcher m = fnPattern.matcher(files.get(i).getName());
            if (m.matches()) {
                String dateStr = m.group(1);
                DateTime time = new DateTime(Integer.parseInt(dateStr.substring(0, 4)),
                        Integer.parseInt(dateStr.substring(4, 6)),
                        Integer.parseInt(dateStr.substring(6, 8)), 0, 0, 0,
                        ISOChronology.getInstanceUTC());
                times.add(time);
            } else {
                throw new EdalException("Problem getting time from filename");
            }
        }

        RegularGrid globalGrid = new RegularGridImpl(BoundingBoxImpl.global(), gridXSize,
                gridYSize);
        TimeAxis timeAxis = new TimeAxisImpl("time", times);

        List<GridVariableMetadata> ret = new ArrayList<>();

        File file = files.get(0);
        NetcdfDataset dataset = null;
        try {
            dataset = NetcdfDatasetAggregator.getDataset(file.getAbsolutePath());
            for (String reqdVar : varsToInclude) {
                Variable var = dataset.findVariable(reqdVar);
                Variable qVar = dataset.findVariable(reqdVar + "_quality_flag");

                if (var == null) {
                    throw new EdalException("Required variable " + reqdVar + " not found");
                }
                if (qVar == null) {
                    throw new EdalException(
                            "Quality control for required variable " + reqdVar + " not found");
                }

                Attribute unitsAttr = var.findAttribute("units");
                String units = unitsAttr == null ? "" : unitsAttr.getStringValue();
                Attribute stdNameAttr = var.findAttribute("standard_name");
                String stdName = stdNameAttr == null ? "" : stdNameAttr.getStringValue();
                Parameter parameter = new Parameter(var.getFullName(), var.getFullName(),
                        var.getDescription(), units, stdName);
                ret.add(new GridVariableMetadata(parameter, globalGrid, null, timeAxis, true));
            }
        } catch (IOException e) {
            throw new EdalException("Problem reading data", e);
        } finally {
            if (dataset != null) {
                NetcdfDatasetAggregator.releaseDataset(dataset);
            }
        }

        return ret;
    }

    @Override
    protected DataReadingStrategy getDataReadingStrategy() {
        /*
         * We want to grid the entire dataset at once
         */
        return DataReadingStrategy.BOUNDING_BOX;
    }

    @Override
    protected GridDataSource openDataSource() throws DataReadingException {
        if (this.gds == null) {
            this.gds = new GridDataSource() {
                @Override
                public void close() throws DataReadingException {
                }

                @Override
                public Array4D<Number> read(String variableId, int tmin, int tmax, int zmin,
                        int zmax, int ymin, int ymax, int xmin, int xmax)
                        throws IOException, DataReadingException {
                    int tSize = tmax - tmin + 1;
                    int zSize = zmax - zmin + 1;
                    int ySize = ymax - ymin + 1;
                    int xSize = xmax - xmin + 1;

                    if (tSize > 1 || zSize > 1) {
                        throw new EdalException("Only single time/z values supported");
                    }

                    ValuesArray4D sums = new ValuesArray4D(tSize, zSize, ySize, xSize);
                    ValuesArray4D totals = new ValuesArray4D(tSize, zSize, ySize, xSize);
                    HorizontalGrid horizontalGrid = getVariableMetadata(variableId)
                            .getHorizontalDomain();
                    /*
                     * Generally tmin=tmax?
                     */
                    for (int t = 0; t < tSize; t++) {
                        /*
                         * Loop over the whole averaging window
                         */
                        for (int fi = tmin + t; fi <= tmin + t + 2 * averagingWindow; fi++) {
                            File file = files.get(fi);
                            NetcdfDataset dataset = null;
                            try {
                                dataset = NetcdfDatasetAggregator
                                        .getDataset(file.getAbsolutePath());
                                /*
                                 * Read the data
                                 */
                                Variable latVar = dataset.findVariable("latitude");
                                Variable lonVar = dataset.findVariable("longitude");
                                Variable var = dataset.findVariable(variableId);
                                Variable qVar = dataset.findVariable(variableId + "_quality_flag");

                                Array latVals = latVar.read();
                                Array lonVals = lonVar.read();
                                Array vals = var.read();
                                Array qVals = qVar.read();
                                long n = vals.getSize();

                                for (int i = 0; i < n; i++) {
                                    byte qVal = qVals.getByte(i);
                                    if (qVal == 0) {
                                        /*
                                         * If the data is good quality, add the
                                         * value to the appropriate grid cell.
                                         */
                                        GridCoordinates2D posIndex = horizontalGrid.findIndexOf(
                                                new HorizontalPosition(lonVals.getFloat(i),
                                                        latVals.getFloat(i)));
                                        Number currentSum = sums.get(t, 0, posIndex.getY(),
                                                posIndex.getX());
                                        if (currentSum == null) {
                                            currentSum = 0f;
                                        }
                                        Number currentTotal = totals.get(t, 0, posIndex.getY(),
                                                posIndex.getX());
                                        if (currentTotal == null) {
                                            currentTotal = 0;
                                        }
                                        sums.set(currentSum.floatValue() + vals.getFloat(i), t, 0,
                                                posIndex.getY(), posIndex.getX());
                                        totals.set(currentTotal.intValue() + 1, t, 0,
                                                posIndex.getY(), posIndex.getX());
                                    }
                                }

                            } catch (IOException e) {
                                throw new EdalException("Problem reading data", e);
                            } finally {
                                if (dataset != null) {
                                    NetcdfDatasetAggregator.releaseDataset(dataset);
                                }
                            }
                        }
                    }

                    ValuesArray4D ret = new ValuesArray4D(tmax - tmin + 1, zmax - zmin + 1,
                            ymax - ymin + 1, xmax - xmin + 1);
                    for (int t = 0; t < tSize; t++) {
                        for (int z = 0; z < zSize; z++) {
                            for (int y = 0; y < ySize; y++) {
                                for (int x = 0; x < xSize; x++) {
                                    Number val = sums.get(t, z, y, x);
                                    if (val != null) {
                                        ret.set(val.doubleValue()
                                                / totals.get(t, z, y, x).doubleValue(), t, z, y, x);
                                    }
                                }
                            }
                        }
                    }
                    return ret;
                }
            };
        }
        return this.gds;
    }

    private static Font LABEL_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 40);

    public static void main(String[] args) throws EdalException, IOException {
        /*
         * The output path for image files
         */
        String outPath = "/home/guy/Data/s4c/output/";

        BufferedImage background = ImageIO
                .read(GOSATGriddedDataset.class.getResource("/blue_marble-4000x2000.png"));

        /*
         * Create an image generator with griddedData single raster layer
         */
        MapImage imageGen = new MapImage();
        /*
         * The actual maximum is around 11500, but only 2 files contain anything
         * like this high. 5000 is griddedData more realistic limit (and we
         * saturate above that anyway)
         */
        SegmentColourScheme colourScheme = new SegmentColourScheme(
                new ScaleRange(1750f, 1900f, false), null, null, new Color(0, true), "psu-plasma",
                250);
        imageGen.getLayers().add(new RasterLayer("xch4", colourScheme));

        GOSATGriddedDataset dataset = new GOSATGriddedDataset("gosat",
                "/home/guy/Data/s4c/ch4/**/*.nc", Arrays.asList(new String[] { "xch4" }), 10, 200,
                100);
        SimpleFeatureCatalogue<GriddedDataset> catalogue = new SimpleFeatureCatalogue<GriddedDataset>(
                dataset, false);

        GridVariableMetadata ch4Metadata = dataset.getVariableMetadata("xch4");
        TimeAxis timeAxis = ch4Metadata.getTemporalDomain();

        DecimalFormat frameNoFormat = new DecimalFormat("0000");
        int frameNo = 0;
//        int width = background.getWidth();
//        int height = background.getHeight();
        int width = 512;
        int height = 256;
        for (DateTime time : timeAxis.getCoordinateValues()) {
            BufferedImage outImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = outImage.createGraphics();
            g.drawImage(background, 0, 0, width, height, null);

            BufferedImage dataImage = imageGen.drawImage(new PlottingDomainParams(width, height,
                    BoundingBoxImpl.global(), null, null, null, null, time), catalogue);
            g.drawImage(dataImage, 0, 0, width, height, null);

            int stringOffsetX = g.getFontMetrics(LABEL_FONT).stringWidth("0000-11-22") / 2;
            int stringOffsetY = g.getFontMetrics(LABEL_FONT).getHeight();
            g.setColor(Color.white);
            g.setFont(LABEL_FONT);
            g.drawString(TimeUtils.formatUtcDateOnly(time), width / 2 - stringOffsetX,
                    4 * height / 5 + 10 + stringOffsetY);

            ImageIO.write(outImage, "png",
                    new File(outPath + "frame-" + frameNoFormat.format(frameNo++) + ".png"));
            System.out.println("Written data for time " + time);
        }

        System.out.println("Finished writing frames.  Now run:\nffmpeg -r 30 -i '" + outPath
                + "frame-%04d.png' -crf 18 -c:v libx264 -pix_fmt yuv420p output.mp4");
    }

    public class RegionText {
        BoundingBox bbox;
        String text;

        public RegionText(BoundingBox bbox, String text) {
            super();
            this.bbox = bbox;
            this.text = text;
        }
    }
}
