//    jDownloader - Downloadmanager
//    Copyright (C) 2009  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.

package jd.gui.swing.jdgui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Toolkit;

import javax.swing.JFrame;

import jd.gui.swing.SwingGui;
import jd.nutils.Screen;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.Storage;

public class GUIUtils {
    public static final Storage STORAGE = JSonStorage.getPlainStorage("gui.windows.dimensionsandlocations");

    public static Dimension getLastDimension(Component child) {
        String key = child.getName();

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int width = screenSize.width;
        int height = screenSize.height;
        Integer x = STORAGE.get("dimension." + key + ".width", -1);
        Integer y = STORAGE.get("dimension." + key + ".height", -1);
        if (x >= 0 && y > 0) {
            if (x > width) x = width;
            if (y > height) y = height;

            return new Dimension(x, y);
        }

        return null;
    }

    public static Point getLastLocation(Component parent, Component child) {
        String key = child.getName();

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int width = screenSize.width;
        int height = screenSize.height;

        Integer x = STORAGE.get("location." + key + ".x", -1);
        Integer y = STORAGE.get("location." + key + ".y", -1);

        if (x > 0 && y > 0) {
            Point point = new Point(x, y);
            if (point.x < 0) point.x = 0;
            if (point.y < 0) point.y = 0;

            /*
             * If the saved point isn't on the screen (can happen after
             * resolution changes) we need to put it back on screen.
             */
            if (point.x > width) point.x = width - 50;
            if (point.y > height) point.y = height - 50;

            return point;
        }

        return Screen.getCenterOfComponent(parent, child);
    }

    public static void restoreWindow(JFrame parent, Component component) {
        if (parent == null) parent = SwingGui.getInstance().getMainFrame();

        component.setLocation(getLastLocation(parent, component));
        Dimension dim = getLastDimension(component);
        if (dim != null) {
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();

            dim.width = Math.min(dim.width, screenSize.width);
            dim.height = Math.min(dim.height, screenSize.height);
            component.setSize(dim);
            if (component instanceof JFrame) {
                ((JFrame) component).setExtendedState(STORAGE.get("extendedstate." + component.getName(), JFrame.NORMAL));
            }
        } else {
            component.validate();
        }

    }

    public static void saveLastDimension(Component child) {
        String key = child.getName();

        boolean max = false;
        if (child instanceof JFrame) {
            STORAGE.put("extendedstate." + key, ((JFrame) child).getExtendedState());
            if (((JFrame) child).getExtendedState() != JFrame.NORMAL) {
                max = true;
            }
        }
        // do not save dimension if frame is not in normal state
        if (!max) {
            STORAGE.put("dimension." + key + ".x", child.getSize().width);
            STORAGE.put("dimension." + key + ".y", child.getSize().height);
        }

    }

    public static void saveLastLocation(Component parent) {
        String key = parent.getName();

        // do not save location if frame is not in normal state
        if (parent instanceof JFrame && ((JFrame) parent).getExtendedState() != JFrame.NORMAL) return;
        if (parent.isShowing()) {
            STORAGE.put("location." + key + ".x", parent.getLocationOnScreen().x);
            STORAGE.put("location." + key + ".y", parent.getLocationOnScreen().y);
        }
    }

}
