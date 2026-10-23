//
// Created by Milk on 4/10/21.
//

#ifndef VIRTUALM_IO_H
#define VIRTUALM_IO_H

#include <jni.h>

#include "BoxCore.h"
#include "PathRules.h"

class IO {
public:
    static void init(JNIEnv *env);

    static PathRules &rules();

    static jstring redirectPath(JNIEnv *env, jstring path);

    static jobject redirectPath(JNIEnv *env, jobject path);

};


#endif //VIRTUALM_IO_H
