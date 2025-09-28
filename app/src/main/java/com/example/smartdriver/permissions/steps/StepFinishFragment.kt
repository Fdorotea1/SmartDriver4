package com.example.smartdriver.permissions.steps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.smartdriver.R

class StepFinishFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_permission_step, container, false)
        v.findViewById<TextView>(R.id.tvHeader).text = "Tudo pronto!"
        v.findViewById<TextView>(R.id.tvExplanation).text =
            "Mais tarde pode gerir permissões no Centro de Permissões (menu da app)."
        v.findViewById<View>(R.id.tvStepStatus).visibility = View.GONE
        v.findViewById<View>(R.id.btnOpenSettings).visibility = View.GONE
        v.findViewById<Button>(R.id.btnIEnabled).visibility = View.GONE
        return v
    }
}
