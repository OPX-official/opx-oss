package com.opx.oss;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/** Terminal entry point: opens the full screen Termux terminal. */
public class TerminalFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        View open = view.findViewById(R.id.terminal_open_button);
        open.setOnClickListener(v ->
                startActivity(new Intent(getContext(), TerminalActivity.class)));
        return view;
    }
}
