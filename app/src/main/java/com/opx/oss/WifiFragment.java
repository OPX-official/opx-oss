package com.opx.oss;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;

/** WiFi section: interface status, scan controls and the network list. Engine wiring lands in Phase 2. */
public class WifiFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_wifi, container, false);

        TextView status = view.findViewById(R.id.wifi_engine_state);
        status.setText(R.string.wifi_state_not_ready);

        View scan = view.findViewById(R.id.wifi_scan_button);
        scan.setOnClickListener(v -> Snackbar.make(v, R.string.wifi_engine_pending, Snackbar.LENGTH_LONG).show());

        return view;
    }
}
