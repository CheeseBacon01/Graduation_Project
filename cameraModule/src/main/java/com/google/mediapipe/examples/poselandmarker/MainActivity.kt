/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.mediapipe.examples.poselandmarker

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.activity.viewModels
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.mediapipe.examples.poselandmarker.databinding.ActivityMainBinding
import com.google.mediapipe.examples.poselandmarker.fragment.PermissionsFragment

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TARGET_FRAGMENT = "extra_target_fragment"

        fun resolveTargetDestination(targetFragment: String): Int? {
            return when (targetFragment) {
                "camera_fragment" -> R.id.camera_fragment
                "stretch_fragment" -> R.id.stretch_fragment
                "chair_stand_fragment" -> R.id.chair_stand_fragment
                "walking_fragment" -> R.id.walking_fragment
                "simulated_sitting_fragment" -> R.id.simulated_sitting_fragment
                "toe_heel_walking_fragment" -> R.id.toe_heel_walking_fragment
                "chair_arm_stretch_fragment" -> R.id.chair_arm_stretch_fragment
                "obstacle_crossing_fragment" -> R.id.obstacle_crossing_fragment
                "bottle_lift_fragment" -> R.id.bottle_lift_fragment
                "squeeze_ball_fragment" -> R.id.squeeze_ball_fragment
                "wring_towel_fragment" -> R.id.wring_towel_fragment
                "balance_test_fragment" -> R.id.balance_test_fragment
                "figure8_walking_fragment" -> R.id.figure8_walking_fragment
                "leg_stretch_fragment" -> R.id.leg_stretch_fragment
                "weighted_leg_stretch_fragment" -> R.id.weighted_leg_stretch_fragment
                "stair_climbing_fragment" -> R.id.stair_climbing_fragment
                else -> null
            }
        }
    }
    private lateinit var activityMainBinding: ActivityMainBinding
    private val viewModel : MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        val navController = navHostFragment.navController
        val targetFragment = intent.getStringExtra(EXTRA_TARGET_FRAGMENT)

        // new
        val startDestination = targetFragment?.let(::resolveTargetDestination)
            ?.takeIf { PermissionsFragment.hasPermissions(this) }
            ?: R.id.permissions_fragment

        val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)
        navGraph.setStartDestination(startDestination)
        navController.graph = navGraph

        activityMainBinding.navigation.setupWithNavController(navController)
        activityMainBinding.navigation.setOnNavigationItemReselectedListener {
            // ignore the reselection
        }
        viewModel.lastResult.observe(this) { result ->
            android.widget.Toast.makeText(
                this,
                "運動完成：${result.exerciseName}\n準確度：${String.format("%.1f", result.accuracy)}%",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onBackPressed() {
        finish()
    }
}