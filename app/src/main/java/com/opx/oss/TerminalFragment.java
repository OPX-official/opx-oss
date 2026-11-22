package com.opx.oss;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;

/** Terminal entry point; the real Termux terminal view arrives in Phase 2. */
public class TerminalFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        View open = view.findViewById(R.id.terminal_open_button);
        open.setOnClickListener(v ->
                Snackbar.make(v, R.string.terminal_pending, Snackbar.LENGTH_LONG).show());
        return view;
    }
}
