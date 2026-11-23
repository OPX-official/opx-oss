package com.opx.oss;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

/**
 * A device card in the shape of Stryker's addusb_dialog: a Lottie USB stick hero, the
 * USB descriptor identity (VID:PID, product, bus/address) as the body, and Close / Save-style
 * text+accent actions. It only <em>shows</em> a device and starts a scan — the Android
 * permission prompt comes from {@code WifiBridge}, not from this dialog.
 */
public class UsbDialog extends DialogFragment {

    public interface Action {
        void onDialogAction();
    }

    private final String title;
    private final String body;
    private final String actionLabel;
    private final Action action;

    public UsbDialog(String title, String body, String actionLabel, Action action) {
        this.title = title;
        this.body = body;
        this.actionLabel = actionLabel;
        this.action = action;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_bg);
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.dialog_usb, container, false);
        ((TextView) root.findViewById(R.id.dialog_title)).setText(title);
        ((TextView) root.findViewById(R.id.dialog_body)).setText(body);
        root.findViewById(R.id.dialog_cancel).setOnClickListener(v -> dismiss());
        root.findViewById(R.id.dialog_action).setOnClickListener(v -> {
            dismiss();
            if (action != null) action.onDialogAction();
        });
        ((TextView) root.findViewById(R.id.dialog_action)).setText(actionLabel);
        return root;
    }
}
