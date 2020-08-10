package com.DicHAc.demo;

/**
 * Created by hasee on 2017/12/19,
 * Edited by DicHAc on 2020/08/01.
 */

public class Face {
    static {
        System.loadLibrary("Face");
    }

    //人脸检测模型导入
    public native boolean FaceDetectionModelInit(String faceDetectionModelPath);

    //人脸检测，返回了bbox，points等信息
    public native int[] FaceDetect(byte[] imageDate, int imageWidth, int imageHeight, int imageChannel);

    // faceInfo的格式：[faceNum,left,top,right,bottom,10*五点,……(重复）]
    public native int[] MaxFaceDetect(byte[] imageDate, int imageWidth, int imageHeight, int imageChannel);

    //人脸检测模型反初始化
    public native boolean FaceDetectionModelUnInit();

    //检测的最小人脸设置
    public native boolean SetMinFaceSize(int minSize);

    //线程设置
    public native boolean SetThreadsNumber(int threadsNumber);

    //循环测试次数
    public native boolean SetTimeCount(int timeCount);

    //返回特征值,jfloatArray型
    public native float[] FaceRecognize(byte[] faceDate1, int w1, int h1, int[] landmarks1);
}
