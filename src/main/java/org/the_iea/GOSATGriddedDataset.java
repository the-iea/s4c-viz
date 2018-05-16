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

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
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

import org.apache.commons.io.FileUtils;
import org.joda.time.DateTime;
import org.joda.time.Days;
import org.joda.time.chrono.ISOChronology;

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
import uk.ac.rdg.resc.edal.util.Extents;
import uk.ac.rdg.resc.edal.util.GridCoordinates2D;
import uk.ac.rdg.resc.edal.util.TimeUtils;
import uk.ac.rdg.resc.edal.util.ValuesArray4D;
import uk.ac.rdg.resc.edal.util.cdm.CdmUtils;

public class GOSATGriddedDataset extends GriddedDataset {
    private static final long serialVersionUID = 1L;

    private int averagingWindow;
    private List<File> files;

    private Map<DateTime, Double> time2GlobalValue = new HashMap<>();

    private GridDataSource gds = null;

    private int bleed;

    public GOSATGriddedDataset(String id, String location, List<String> varsToInclude,
            int averagingWindow, int gridXSize, int gridYSize, int bleed) {
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

        this.bleed = bleed;
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
                                        /*
                                         * Bleed the values out to surrounding
                                         * cells if required
                                         */
                                        for (int xi = -bleed; xi <= bleed; xi++) {
                                            int x = posIndex.getX() + xi;
                                            if(x < 0) {
                                                x += xSize;
                                            }
                                            if(x >= xSize) {
                                                x -= xSize;
                                            }
                                            for (int yi = -bleed; yi <= bleed; yi++) {
                                                int y = posIndex.getY() + yi;
                                                if (y < 0 || y >= ySize) {
                                                    continue;
                                                }
                                                Number currentSum = sums.get(t, 0, y, x);
                                                if (currentSum == null) {
                                                    currentSum = 0f;
                                                }
                                                Number currentTotal = totals.get(t, 0, y, x);
                                                if (currentTotal == null) {
                                                    currentTotal = 0;
                                                }
                                                sums.set(currentSum.floatValue() + vals.getFloat(i),
                                                        t, 0, y, x);
                                                totals.set(currentTotal.intValue() + 1, t, 0, y, x);
                                            }
                                        }
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
                        double sum = 0.0;
                        int n = 0;
                        for (int z = 0; z < zSize; z++) {
                            for (int y = 0; y < ySize; y++) {
                                for (int x = 0; x < xSize; x++) {
                                    Number val = sums.get(t, z, y, x);
                                    if (val != null) {
                                        double pointVal = val.doubleValue()
                                                / totals.get(t, z, y, x).doubleValue();
                                        ret.set(pointVal, t, z, y, x);
                                        sum += pointVal;
                                        n++;
                                    }
                                }
                            }
                        }
                        time2GlobalValue.put(getVariableMetadata(variableId).getTemporalDomain()
                                .getCoordinateValue(tmin + t), sum / n);
                    }
                    return ret;
                }
            };
        }
        return this.gds;
    }

    public static void main(String[] args) throws EdalException, IOException {
        System.out.println("STARTED: " + new DateTime());

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
         * Empirical scale range, found to give a good contrast
         */
        SegmentColourScheme colourScheme = new SegmentColourScheme(
                new ScaleRange(1750f, 1875f, false), null, null, new Color(0, true), "psu-plasma",
                250);
        imageGen.getLayers().add(new RasterLayer("xch4", colourScheme));

        GOSATGriddedDataset dataset = new GOSATGriddedDataset("gosat",
                "/home/guy/Data/s4c/ch4/**/*.nc", Arrays.asList(new String[] { "xch4" }), 10, 3600,
                1800, 10);
        SimpleFeatureCatalogue<GriddedDataset> catalogue = new SimpleFeatureCatalogue<GriddedDataset>(
                dataset, false);

        GridVariableMetadata ch4Metadata = dataset.getVariableMetadata("xch4");
        TimeAxis timeAxis = ch4Metadata.getTemporalDomain();

        DecimalFormat frameNoFormat = new DecimalFormat("00000");
        int frameNo = 0;
        int width = background.getWidth();
        int height = background.getHeight();

//        width = 400;
//        height = 200;

        Font labelFont = new Font(Font.MONOSPACED, Font.PLAIN, height / 25);
        Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, height / 40);
        Font annotationFont = new Font(Font.SANS_SERIF, Font.PLAIN, height / 50);
        BasicStroke dashStroke = new BasicStroke(height / 250f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 10f, new float[] { 10f, 2f }, 0f);
        BasicStroke solidStroke = new BasicStroke(height / 250f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER);

        DateTime startTime = timeAxis.getCoordinateValue(0);
        for (DateTime time : timeAxis.getCoordinateValues()) {
            if (time.isBefore(startTime)) {
                continue;
            }

            List<Annotation> annotations = getAnnotations(time, regions);

            int slowdownFactor;
            int maxSlowdown = 10;
            if (annotations.size() == 0) {
                slowdownFactor = 1;
            } else {
                /*
                 * Slow down if we are displaying an annotation
                 */
                slowdownFactor = maxSlowdown;
            }

            BufferedImage outImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = outImage.createGraphics();
            g.drawImage(background, 0, 0, width, height, null);

            PlottingDomainParams params = new PlottingDomainParams(width, height,
                    BoundingBoxImpl.global(), null, null, null, null, time);
            BufferedImage dataImage = imageGen.drawImage(params, catalogue);
            g.drawImage(dataImage, 0, 0, width, height, null);

            int stringOffsetX = g.getFontMetrics(labelFont).stringWidth("0000-11-22") / 2;
            int stringOffsetY = g.getFontMetrics(labelFont).getHeight();
            g.setColor(Color.white);
            g.setFont(labelFont);
            g.drawString(TimeUtils.formatUtcDateOnly(time), width / 2 - stringOffsetX,
                    4 * height / 5 + 10 + stringOffsetY);

            if (annotations.size() > 0) {
                /*
                 * We want to add an annotation to the image and slow it down.
                 */
                g.setFont(annotationFont);

                RegularGrid imageGrid = params.getImageGrid();
                int fadeDays = 8;
                Annotation annotation = annotations.get(0);
                /*
                 * Calculate how far we are through the annotation. If we're in
                 * the first/last x%, fade in/out and set the slowdownFactor
                 * accordingly to ramp the speed up/down
                 */
                int in = Math.abs(Days.daysBetween(annotation.dateRange.getLow(), time).getDays());
                int out = Math
                        .abs(Days.daysBetween(annotation.dateRange.getHigh(), time).getDays());

                float opacity = 1f;
                boolean fadeIn = true;
                if (in < fadeDays) {
                    opacity = ((float) in) / fadeDays;
                } else if (out < fadeDays) {
                    opacity = ((float) out) / fadeDays;
                    fadeIn = false;
                }

                if (opacity < 1) {
                    slowdownFactor = 1 + (int) (opacity * maxSlowdown);

                    /*
                     * Ramp the opacity up / down
                     */
                    if (fadeIn) {
                        for (float o = opacity; o < opacity + 1f / fadeDays; o += 1f
                                / (fadeDays * slowdownFactor)) {
                            BufferedImage fadeImage = new BufferedImage(width, height,
                                    BufferedImage.TYPE_INT_ARGB);
                            Graphics2D g2 = fadeImage.createGraphics();
                            g2.drawImage(outImage, 0, 0, width, height, null);
                            drawAnnotation(annotation, g2, imageGrid, titleFont, annotationFont,
                                    dashStroke, solidStroke, o);
                            File outFile = new File(
                                    outPath + "frame-" + frameNoFormat.format(frameNo++) + ".png");
                            ImageIO.write(fadeImage, "png", outFile);
                        }
                        System.out.println("Written data for time " + time);
                        continue;
                    } else {
                        for (float o = opacity; o > opacity - 1f / fadeDays; o -= 1f
                                / (fadeDays * slowdownFactor)) {
                            BufferedImage fadeImage = new BufferedImage(width, height,
                                    BufferedImage.TYPE_INT_ARGB);
                            Graphics2D g2 = fadeImage.createGraphics();
                            g2.drawImage(outImage, 0, 0, width, height, null);
                            drawAnnotation(annotation, g2, imageGrid, titleFont, annotationFont,
                                    dashStroke, solidStroke, o);
                            File outFile = new File(
                                    outPath + "frame-" + frameNoFormat.format(frameNo++) + ".png");
                            ImageIO.write(fadeImage, "png", outFile);
                        }
                        System.out.println("Written data for time " + time);
                        continue;
                    }
                } else {
                    slowdownFactor = maxSlowdown;
                    drawAnnotation(annotation, g, imageGrid, titleFont, annotationFont, dashStroke,
                            solidStroke, 1f);
                }
            }

            File outFile = new File(outPath + "frame-" + frameNoFormat.format(frameNo++) + ".png");
            ImageIO.write(outImage, "png", outFile);
            for (int i = 1; i < slowdownFactor; i++) {
                FileUtils.copyFile(outFile,
                        new File(outPath + "frame-" + frameNoFormat.format(frameNo++) + ".png"));
            }
            System.out.println("Written data for time " + time);
        }

        System.out.println("Finished writing frames.  Now run:\nffmpeg -r 30 -i '" + outPath
                + "frame-%05d.png' -crf 18 -c:v libx264 -pix_fmt yuv420p output.mp4");

        System.out.println("FINISHED: " + new DateTime());

    }

    /**
     * 
     * @param g
     * @param text
     *            The text to draw. Supports \n
     * @param annotationFont
     *            The font to use
     * @param x
     *            The position of the edge of the box
     * @param xLeft
     *            <code>true</code> if it's the left edge, <code>false</code> if
     *            it's the right
     * @param cy
     *            The vertical position of the centre of the box
     * @param stroke
     */
    private static void drawAnnotation(Annotation annotation, Graphics2D g, RegularGrid imageGrid,
            Font titleFont, Font annotationFont, Stroke roiStroke, Stroke textboxStroke,
            float opacity) {
        g.setComposite(AlphaComposite.SrcOver.derive(opacity));

        /*
         * Draw a box around the area of interest
         */
        GridCoordinates2D llCoords = imageGrid.findIndexOf(annotation.bbox.getLowerCorner());
        GridCoordinates2D urCoords = imageGrid.findIndexOf(annotation.bbox.getUpperCorner());

        g.setColor(Color.white);
        g.setStroke(roiStroke);

        int height = imageGrid.getYSize();
        g.draw(new RoundRectangle2D.Double(llCoords.getX(), height - 1 - urCoords.getY(),
                urCoords.getX() - llCoords.getX(), urCoords.getY() - llCoords.getY(), 10, 10));

        /*
         * Calculate where to put the text annotation and the draw it
         */
        int x;
        boolean xLeft;
        if (annotation.bbox.getMinX() + annotation.bbox.getWidth() >= 0) {
            xLeft = false;
            x = llCoords.getX() - 10;
        } else {
            xLeft = true;
            x = urCoords.getX() + 10;
        }
//        int cy = height - 1 - (llCoords.getY() + urCoords.getY()) / 2;
        int cy = height / 2;

        /*
         * Draws the specified text in a box.
         */
        String[] lines = annotation.text.split("\n");

        FontMetrics titleFontMetrics = g.getFontMetrics(titleFont);
        int titleHeight = titleFontMetrics.getHeight();

        FontMetrics mainFontMetrics = g.getFontMetrics(annotationFont);
        int lineHeight = mainFontMetrics.getHeight();
        int lineGap = (int) (0.1 * lineHeight);

        int totalHeight = lines.length * (lineHeight + lineGap) + 3 * lineGap + titleHeight;

        int maxLineWidth = 0;
        for (String line : lines) {
            maxLineWidth = Math.max(maxLineWidth, mainFontMetrics.stringWidth(line));
        }
        maxLineWidth = Math.max(maxLineWidth, titleFontMetrics.stringWidth(annotation.title));
        int edgeGap = lineGap;

        /*
         * Draw the box to go around the annotation
         */
        int boxW = maxLineWidth + 2 * edgeGap;
        g.setColor(new Color(255, 255, 255));
        g.setStroke(textboxStroke);
        g.draw(new Rectangle2D.Double(xLeft ? x : x - boxW, cy - totalHeight / 2, boxW,
                totalHeight));
        g.setColor(new Color(255, 255, 255, 192));
        g.fill(new Rectangle2D.Double(xLeft ? x : x - boxW, cy - totalHeight / 2, boxW,
                totalHeight));

        int yOff = cy - totalHeight / 2 - lineGap + titleHeight;
        g.setColor(Color.black);

        g.setFont(titleFont);
        int sWidth = titleFontMetrics.stringWidth(annotation.title);
        g.drawString(annotation.title,
                (xLeft ? x : x - boxW) + edgeGap + (maxLineWidth - sWidth) / 2, yOff);
        // Double line gap after title
        yOff += lineGap;

        g.setFont(annotationFont);
        for (String line : lines) {
            yOff += lineGap + lineHeight;
            sWidth = mainFontMetrics.stringWidth(line);
            g.drawString(line, (xLeft ? x : x - boxW) + edgeGap + (maxLineWidth - sWidth) / 2,
                    yOff);
        }

        g.setComposite(AlphaComposite.SrcOver);
    }

    private static List<Annotation> getAnnotations(DateTime time, List<Annotation> regionData) {
        List<Annotation> ret = new ArrayList<>();
        for (Annotation testData : regionData) {
            if (testData.dateRange.contains(time)) {
                ret.add(testData);
            }
        }
        return ret;
    }

    private static class Annotation {
        Extent<DateTime> dateRange;
        BoundingBox bbox;
        String title;
        String text;

        public Annotation(Extent<DateTime> dateRange, BoundingBox bbox, String title, String text) {
            this.dateRange = dateRange;
            this.bbox = bbox;
            this.title = title;
            this.text = text;
        }
    }

    /*
     * This contains a list of annotations, at which point the animation speed
     * should be slowed down and the text and bounding box drawn on the globe.
     * 
     * TODO convert to list containing all info, and return percentage of time
     * extent for fade in / out
     */
    private static List<Annotation> regions = new ArrayList<>();
    static {
        regions.add(new Annotation(
//                Extents.newExtent(new DateTime(2017, 2, 1, 0, 0, 0),
//                        new DateTime(2017, 2, 28, 0, 0, 0)),
                Extents.newExtent(new DateTime(2009, 8, 15, 0, 0, 0),
                        new DateTime(2009, 10, 15, 0, 0, 0)),
                new BoundingBoxImpl(-130, 20, -70, 55), "North American Emissions",
                "GOSAT CH₄ data is used to estimate\n" + "North American methane emissions\n"
                        + "and finds that emissions are highest\n"
                        + "in south-central US, the Central Valley\n"
                        + "of California and the Florida wetlands.\n"
                        + "Large isolated point sources such as\n"
                        + "the US Four Corners methane coalbed\n" + "are also identified\n"
                        + "- Turner et al, 2015"));
        regions.add(new Annotation(
//                Extents.newExtent(new DateTime(2017, 4, 1, 0, 0, 0),
//                new DateTime(2017, 4, 30, 0, 0, 0)),
                Extents.newExtent(new DateTime(2010, 2, 1, 0, 0, 0),
                        new DateTime(2010, 3, 28, 0, 0, 0)),
                new BoundingBoxImpl(-68, -40, -48, -8), "South American Wetlands",
                "Natural wetlands are the largest source of methane\n"
                        + "emissions, contributing 20-40% of global CH₄ emissions\n"
                        + "and dominating the inter-annual variability. Large\n"
                        + "uncertainties remain on their variability and response\n"
                        + "to climate change. We use GOSAT CH₄ data to compare\n"
                        + "against models and identify discrepancies where the\n"
                        + "models are failing to match the observations. One such\n"
                        + "example is over the Paraná River in South America where\n"
                        + "massive flooding of the river led to large wetland areas\n"
                        + "producing methane that were missed by the model\n"
                        + "- Parker et al, 2018."));
        regions.add(new Annotation(
//                Extents.newExtent(new DateTime(2017, 6, 1, 0, 0, 0),
//                new DateTime(2017, 6, 30, 0, 0, 0)),
                Extents.newExtent(new DateTime(2012, 8, 15, 0, 0, 0),
                        new DateTime(2012, 10, 15, 0, 0, 0)),
                new BoundingBoxImpl(60, 6, 135, 42), "Monitoring country-scale emissions",
                "Enhanced methane values over India/China\n"
                        + "related to rice paddy and ruminant emissions\n"
                        + "are observed during August – October each year.\n"
                        + "This data has been used to monitor India’s\n"
                        + "methane emissions and confirm consistency\n"
                        + "with the values that India reports to the United\n"
                        + "Framework Convention on Climate Change\n" + "- Ganesan et al., 2017"));
        regions.add(new Annotation(
//                Extents.newExtent(new DateTime(2017, 8, 1, 0, 0, 0),
//                new DateTime(2017, 8, 31, 0, 0, 0)),
                Extents.newExtent(new DateTime(2014, 3, 1, 0, 0, 0),
                        new DateTime(2014, 4, 30, 0, 0, 0)),
                new BoundingBoxImpl(-80, -20, -40, 5), "Understanding the Amazon",
                "To use this satellite data over remote\n"
                        + "regions like the Amazon, it is important\n"
                        + "to validate the data. We compared the\n"
                        + "satellite CH₄ values to those taken by\n"
                        + "dedicated aircraft measurements over the\n"
                        + "Amazon and found very good agreement\n" + "- Webb et al., 2016"));
        regions.add(new Annotation(
//                Extents.newExtent(new DateTime(2017, 10, 1, 0, 0, 0),
//                new DateTime(2017, 10, 30, 0, 0, 0)),
                Extents.newExtent(new DateTime(2015, 9, 1, 0, 0, 0),
                        new DateTime(2015, 10, 31, 0, 0, 0)),
                new BoundingBoxImpl(90, -15, 150, 15), "Identifying what's burning",
                "The 2015-2016 strong El Niño event had a\n"
                        + "dramatic impact on the amount of Indonesian\n"
                        + "biomass burning. By using GOSAT CH₄ and CO₂\n"
                        + "measurements, we were able to examine the\n"
                        + "combustion behaviour of these fires and identify\n"
                        + "significant amounts of smouldering combustion,\n"
                        + "indicative of carbon-rich peatland burning \n"
                        + "- Parker et al., 2016"));
    }

}