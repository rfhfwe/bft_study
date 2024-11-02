package com.pbft;

public class Common {
    public static final int CV = -2; // 视图变更
    public static final int VIEW = -1; // 请求视图
    public static final int REQ = 0; // 请求数据
    public static final int PP = 1; // 预准备阶段
    public static final int PA = 2; // 准备阶段
    public static final int CM = 3; // 提交阶段
    public static final int REPLY = 4; // 回复

    public static final int HREQ = 5; //请求数据
    public static final int HPP = 6;  // 预准备阶段
    public static final int HBA = 7;  // 回复阶段
    public static final int HCON = 8; // 确认阶段
    public static final int HCOM = 9; // 回复
}
