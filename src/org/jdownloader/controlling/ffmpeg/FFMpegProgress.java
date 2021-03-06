package org.jdownloader.controlling.ffmpeg;

import javax.swing.Icon;

import jd.plugins.PluginProgress;

import org.jdownloader.gui.IconKey;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.gui.views.downloads.columns.ETAColumn;
import org.jdownloader.gui.views.downloads.columns.ProgressColumn;
import org.jdownloader.images.AbstractIcon;
import org.jdownloader.plugins.PluginTaskID;

public class FFMpegProgress extends PluginProgress {

    public FFMpegProgress() {
        super(0, 100, null);
        setIcon(new AbstractIcon(IconKey.ICON_LOGO_FFMPEG, 18));
    }

    @Override
    public PluginTaskID getID() {
        return PluginTaskID.FFMPEG;
    }

    @Override
    public Icon getIcon(Object requestor) {
        if (requestor instanceof ETAColumn) {
            return null;
        }
        return super.getIcon(requestor);
    }

    @Override
    public String getMessage(Object requestor) {
        if (requestor instanceof ETAColumn) {
            return null;
        }
        if (requestor instanceof ProgressColumn) {
            return null;
        }
        return getDetaultMessage();
    }

    protected String getDetaultMessage() {
        return _GUI.T.FFMpegProgress_getMessage_merging();
    }

}
