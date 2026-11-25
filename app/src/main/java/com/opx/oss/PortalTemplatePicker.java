package com.opx.oss;

import android.app.AlertDialog;
import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * OPX-wifi4's template catalog as a two-step picker: category, then page, with a custom HTML
 * file as the last choice. Used by both sheets that host a portal (AP crack mode and Evil Twin).
 */
final class PortalTemplatePicker {

    interface Listener {
        void onPicked(File file, String label);

        /** The user wants a page from their own storage; the screen opens the document picker. */
        void onCustom();
    }

    private PortalTemplatePicker() {
    }

    static void show(Context context, Listener listener) {
        List<String> categories = PortalTemplates.categories(context);
        List<String> labels = new ArrayList<>();
        for (String category : categories) labels.add(PortalTemplates.categoryLabel(category));
        labels.add(context.getString(R.string.portal_template_custom));
        new AlertDialog.Builder(context)
                .setTitle(R.string.portal_template_category)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which == categories.size()) {
                        listener.onCustom();
                        return;
                    }
                    showCategory(context, categories.get(which), listener);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showCategory(Context context, String category, Listener listener) {
        List<PortalTemplates.Entry> entries = PortalTemplates.templates(context, category);
        if (entries.isEmpty()) {
            listener.onCustom();
            return;
        }
        List<String> labels = new ArrayList<>();
        for (PortalTemplates.Entry entry : entries) labels.add(entry.label);
        new AlertDialog.Builder(context)
                .setTitle(PortalTemplates.categoryLabel(category))
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    PortalTemplates.Entry entry = entries.get(which);
                    try {
                        File file = PortalTemplates.materialize(context, entry);
                        listener.onPicked(file, PortalTemplates.categoryLabel(category)
                                + " · " + entry.label);
                    } catch (Exception failed) {
                        AppLog.error("OpxPortal", failed);
                        listener.onCustom();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
