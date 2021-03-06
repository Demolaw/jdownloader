package org.jdownloader.gui.views.components.packagetable.context;

import java.awt.event.ActionEvent;
import java.util.List;

import org.appwork.utils.event.queue.QueueAction;
import org.appwork.utils.swing.dialog.Dialog;
import org.appwork.utils.swing.dialog.DialogNoAnswerException;
import org.jdownloader.controlling.contextmenu.CustomizableTableContextAppAction;
import org.jdownloader.gui.IconKey;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.images.AbstractIcon;

import jd.controlling.TaskQueue;
import jd.controlling.linkcrawler.CrawledLink;
import jd.controlling.packagecontroller.AbstractNode;
import jd.controlling.packagecontroller.AbstractPackageChildrenNode;
import jd.controlling.packagecontroller.AbstractPackageNode;
import jd.plugins.DownloadLink;

public class SetDownloadPassword<PackageType extends AbstractPackageNode<ChildrenType, PackageType>, ChildrenType extends AbstractPackageChildrenNode<PackageType>> extends CustomizableTableContextAppAction<PackageType, ChildrenType> {

    /**
     *
     */
    private static final long serialVersionUID = -8280535886054721277L;

    public SetDownloadPassword() {
        super();
        setName(_GUI.T.SetDownloadPassword_SetDownloadPassword_());
        setIconKey(IconKey.ICON_PASSWORD);
    }

    public void actionPerformed(ActionEvent e) {
        if (!isEnabled()) {
            return;
        }
        final List<ChildrenType> newSelection = getSelection().getChildren();
        String defaultPW = "";
        AbstractNode node = newSelection.get(0);
        if (node instanceof DownloadLink) {
            defaultPW = ((DownloadLink) node).getDownloadPassword();
        } else if (node instanceof CrawledLink) {
            defaultPW = ((CrawledLink) node).getDownloadLink().getDownloadPassword();
        }
        try {
            final String newPW = Dialog.getInstance().showInputDialog(0, _GUI.T.SetDownloadPassword_SetDownloadPassword_(), _GUI.T.jd_gui_userio_defaulttitle_input(), defaultPW, new AbstractIcon(IconKey.ICON_PASSWORD, 32), null, null);
            TaskQueue.getQueue().add(new QueueAction<Void, RuntimeException>() {

                @Override
                protected Void run() throws RuntimeException {
                    for (AbstractNode l : newSelection) {
                        DownloadLink dl = null;
                        if (l instanceof CrawledLink) {
                            dl = ((CrawledLink) l).getDownloadLink();
                        } else if (l instanceof DownloadLink) {
                            dl = (DownloadLink) l;
                        }
                        if (dl != null) {
                            dl.setDownloadPassword(newPW);
                        }
                    }
                    return null;
                }
            });
        } catch (DialogNoAnswerException e1) {
        }
    }

}
