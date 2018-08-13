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
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import javax.imageio.ImageIO;

import org.joda.time.DateTime;

import uk.ac.rdg.resc.edal.dataset.Domain2DMapper;
import uk.ac.rdg.resc.edal.dataset.DomainMapper.DomainMapperEntry;
import uk.ac.rdg.resc.edal.exceptions.EdalException;
import uk.ac.rdg.resc.edal.geometry.BoundingBoxImpl;
import uk.ac.rdg.resc.edal.graphics.style.ColourScheme;
import uk.ac.rdg.resc.edal.graphics.style.ScaleRange;
import uk.ac.rdg.resc.edal.graphics.style.SegmentColourScheme;
import uk.ac.rdg.resc.edal.grid.RegularAxisImpl;
import uk.ac.rdg.resc.edal.grid.RegularGrid;
import uk.ac.rdg.resc.edal.grid.RegularGridImpl;
import uk.ac.rdg.resc.edal.util.GISUtils;

/**
 * Reads the .ASC data of water quality and plots on a high-resolution image.
 * 
 * This just plots the data. The final composition of the image with branding /
 * colourbar etc. was done in image manipulation software..
 *
 * @author Guy Griffiths
 */
public class RenderWaterQuality {
    public static void main(String[] args) throws EdalException, IOException {
        /*
         * The output path for image files
         */
        String outPath = "/home/guy/Data/s4c/output-wq/";

        File inFile = new File("/home/guy/Data/s4c/wq/MeanPcWatBalPoll.asc");

        int width = 4320;
        int height = 2160;
        RegularGrid imageGrid = new RegularGridImpl(BoundingBoxImpl.global(), width, height);
        RegularGrid dataGrid;
        Float[][] data;

        try (BufferedReader reader = new BufferedReader(new FileReader(inFile))) {
            String nColsStr = reader.readLine();
            int nCols = Integer.parseInt(nColsStr.split("\\s+")[1]);
            String nRowsStr = reader.readLine();
            int nRows = Integer.parseInt(nRowsStr.split("\\s+")[1]);

            String xllcStr = reader.readLine();
            float xllc = Float.parseFloat(xllcStr.split("\\s+")[1]);
            String yllcStr = reader.readLine();
            float yllc = Float.parseFloat(yllcStr.split("\\s+")[1]);
            String cellSizeStr = reader.readLine();
            float cellSize = Float.parseFloat(cellSizeStr.split("\\s+")[1]);

            String noDataStr = reader.readLine();
            float noData = Float.parseFloat(noDataStr.split("\\s+")[1]);

            dataGrid = new RegularGridImpl(
                    new RegularAxisImpl("x", xllc, cellSize, nCols, true), new RegularAxisImpl("y",
                            yllc + (nRows * cellSize) - (cellSize / 2f), -cellSize, nRows, false),
                    GISUtils.defaultGeographicCRS());
            data = new Float[nRows][nCols];
            for (int r = 0; r < nRows; r++) {
                String line = reader.readLine().trim();
                String[] rowValsStr = line.split("\\s+");
                int c = 0;
                for (String rowValStr : rowValsStr) {
                    Float val = Float.parseFloat(rowValStr);
                    if (val == noData) {
                        val = null;
                    }
                    data[r][c++] = val;
                }
            }
        }

        Domain2DMapper mapper = Domain2DMapper.forGrid(dataGrid, imageGrid);
        Float min = Float.MAX_VALUE;
        Float max = -Float.MAX_VALUE;

        List<String> palettes = Arrays.asList(new String[] { "div-Spectral-inv" });
        for (String pal : palettes) {
            BufferedImage output = ImageIO
                    .read(GOSATGriddedDataset.class.getResource("/blue_marble-4320x2160.png"));
            Graphics2D g = output.createGraphics();
            /*
             * Dim the map by plotting a semi-transparent black rectangle on top
             */
            g.setColor(new Color(0, 0, 0, 0.5f));
            g.fillRect(0, 0, width, height);
            ColourScheme cs = new SegmentColourScheme(new ScaleRange(1f, 30f, false),
                    new Color(0, true), null, null, pal, 250);

            Iterator<DomainMapperEntry<int[]>> it = mapper.iterator();
            while (it.hasNext()) {
                DomainMapperEntry<int[]> dme = it.next();
                Float value = data[dme.getSourceGridJIndex()][dme.getSourceGridIIndex()];
                if (value != null) {
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                    Color color = cs.getColor(value);
                    if (color.getAlpha() > 0) {
                        for (int[] i : dme.getTargetIndices()) {
                            output.setRGB(i[0], output.getHeight() - 1 - i[1], color.getRGB());
                        }
                    }
                }
            }

            BufferedImage scaleBar = cs.getScaleBar(2160, 300, 0.1f, false, true, Color.white,
                    new Color(0, true));
//            g.drawImage(scaleBar, 200, 1000 - scaleBar.getHeight() / 2, scaleBar.getWidth(),
//                    scaleBar.getHeight(), null);
//            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, height / 60));
//            g.drawString("Water Quality Index (%)", 250 + scaleBar.getWidth(), 1000);

//            BufferedImage logo = ImageIO.read(new File("/home/guy/Data/s4c/wq/WaterWorld_logo.png"));
//            g.drawImage(logo, 3900 - logo.getWidth(), 1000 - logo.getHeight() / 2, logo.getWidth(), logo.getHeight(), null);

            ImageIO.write(output, "png", new File(outPath, "wq.png"));
            ImageIO.write(scaleBar, "png", new File(outPath, "wq-scale.png"));
            System.out.println("Wrote for palette: " + pal);
        }

        System.out.println("FINISHED: " + new DateTime());
    }
}