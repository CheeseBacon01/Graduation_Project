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
package com.google.mediapipe.examples.poselandmarker.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnGaitTest.setOnClickListener {
            findNavController().navigate(R.id.home_fragment) // Placeholder if actual fragment not specified, but let's fix to match others
            findNavController().navigate(R.id.action_home_to_camera)
        }
        binding.btnStretchTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_stretch)
        }
        binding.btnChairStandTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_chair_stand)
        }
        binding.btnWalkingTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_walking)
        }
        binding.btnWalkingBTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_walking_b)
        }
        binding.btnWalkingBWeek7Test.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_walking_b_week7)
        }
        binding.btnWalkingCTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_walking_c)
        }
        binding.btnWalkingCWeek7Test.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_walking_c_week7)
        }
        binding.btnWalkingDTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_walking_d)
        }
        binding.btnWalkingDWeek7Test.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_walking_d_week7)
        }
        binding.btnSimulatedSittingTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_simulated_sitting)
        }
        binding.btnToeHeelWalkingTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_toe_heel_walking)
        }
        binding.btnChairArmStretchTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_chair_arm_stretch)
        }
        binding.btnObstacleCrossingTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_obstacle_crossing)
        }
        binding.btnBottleLiftTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_bottle_lift)
        }
        binding.btnSqueezeBallTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_squeeze_ball)
        }
        binding.btnWringTowelTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_wring_towel)
        }
        binding.btnBalanceTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_balance_test)
        }
        binding.btnGaitSpeed4mTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_gait_speed_4m)
        }
        binding.btnFiveTimesChairStandTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_five_times_chair_stand)
        }
        binding.btnTimedUpAndGoTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_timed_up_and_go)
        }
        binding.btnGaitSpeed6mTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_gait_speed_6m)
        }
        binding.btnFigure8Walking.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_figure8_walking)
        }
        binding.btnLegStretchTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_leg_stretch)
        }
        binding.btnWeightedLegStretchTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_weighted_leg_stretch)
        }
        binding.btnStairClimbingTest.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_stair_climbing)
        }
        binding.btnBalloonWalking.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_balloon_walking)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
