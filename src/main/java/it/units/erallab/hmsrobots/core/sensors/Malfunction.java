/*
 * Copyright (C) 2021 Eric Medvet <eric.medvet@gmail.com> (as Eric Medvet <eric.medvet@gmail.com>)
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package it.units.erallab.hmsrobots.core.sensors;

import it.units.erallab.hmsrobots.core.objects.BreakableVoxel;
import it.units.erallab.hmsrobots.util.DoubleRange;

/**
 * @author eric
 */
public class Malfunction extends AbstractSensor {

  private final static DoubleRange[] DOMAINS = new DoubleRange[]{
      DoubleRange.of(0d, 1d)
  };

  public Malfunction() {
    super(DOMAINS);
  }

  @Override
  public double[] sense(double t) {
    if (voxel instanceof BreakableVoxel) {
      if (((BreakableVoxel) voxel).isBroken()) {
        return new double[]{1d};
      }
    }
    return new double[]{0d};
  }

}
