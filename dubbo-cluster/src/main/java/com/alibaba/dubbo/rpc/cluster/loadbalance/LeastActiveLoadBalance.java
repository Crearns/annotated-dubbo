/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.cluster.loadbalance;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcStatus;

import java.util.List;
import java.util.Random;

/**
 * LeastActiveLoadBalance
 *
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "leastactive";

    private final Random random = new Random();

    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        int length = invokers.size(); // Number of invokers  总个数
        int leastActive = -1; // The least active value of all invokers 最小的活跃数
        int leastCount = 0; // The number of invokers having the same least active value (leastActive) 相同最小活跃数的个数
        int[] leastIndexs = new int[length]; // The index of invokers having the same least active value (leastActive) 相同最小活跃数的下标
        int totalWeight = 0; // The sum of weights 总权重
        int firstWeight = 0; // Initial value, used for comparision 第一个权重 用于计算是否相同
        boolean sameWeight = true; // Every invoker has the same weight value? 是否所有权重相同
        // 计算获得相同最小活跃数的数组和个数
        for (int i = 0; i < length; i++) {
            Invoker<T> invoker = invokers.get(i);
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive(); // Active number 活跃数
            int weight = invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.WEIGHT_KEY, Constants.DEFAULT_WEIGHT); // 权重
            if (leastActive == -1 || active < leastActive) { // Restart, when find a invoker having smaller least active value. 发现更小的活跃数，重新开始
                leastActive = active; // Record the current least active value 记录最小活跃数
                leastCount = 1; // Reset leastCount, count again based on current leastCount 重新统计相同最小活跃数的个数
                leastIndexs[0] = i; // Reset 重新记录最小活跃数下标
                totalWeight = weight; // Reset 重新累计总权重
                firstWeight = weight; // Record the weight the first invoker 记录第一个权重
                sameWeight = true; // Reset, every invoker has the same weight value? 还原权重相同标识
            } else if (active == leastActive) { // If current invoker's active value equals with leaseActive, then accumulating. 累计相同最小的活跃数
                leastIndexs[leastCount++] = i; // Record index number of this invoker 累计相同最小活跃数下标
                totalWeight += weight; // Add this invoker's weight to totalWeight. 累计总权重
                // If every invoker has the same weight?
                // 判断所有权重是否一样
                if (sameWeight && i > 0
                        && weight != firstWeight) {
                    sameWeight = false;
                }
            }
        }
        // assert(leastCount > 0)
        if (leastCount == 1) {
            // 如果只有一个最小则直接返回
            // If we got exactly one invoker having the least active value, return this invoker directly.
            return invokers.get(leastIndexs[0]);
        }
        if (!sameWeight && totalWeight > 0) {
            // 如果权重不相同且权重大于0则按总权重数随机
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            int offsetWeight = random.nextInt(totalWeight);
            // Return a invoker based on the random value.
            // 并确定随机值落在哪个片断上
            for (int i = 0; i < leastCount; i++) {
                int leastIndex = leastIndexs[i];
                offsetWeight -= getWeight(invokers.get(leastIndex), invocation);
                if (offsetWeight <= 0)
                    return invokers.get(leastIndex);
            }
        }
        // 如果权重相同或权重为0则均等随机
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        return invokers.get(leastIndexs[random.nextInt(leastCount)]);
    }
}