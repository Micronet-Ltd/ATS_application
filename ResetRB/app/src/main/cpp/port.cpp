/*
 * Copyright 2009 Cedric Priscal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * NOTICE:
 *      Changes were made from the original code to implement the way ports
 *      are being opened and how logging is handled. Code was modified to
 *      apply to multiple classes (multiple variations of the code was made).
 */

#include <jni.h>
#include <string>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <termios.h>

#include "android/log.h"
#include <unistd.h>

static const char *TAG="ModemUpdater_C_Code";
#define LOGI(fmt, args...) __android_log_print(ANDROID_LOG_INFO,  TAG, fmt, ##args)
#define LOGD(fmt, args...) __android_log_print(ANDROID_LOG_DEBUG, TAG, fmt, ##args)
#define LOGE(fmt, args...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##args)

static speed_t getBaudrate(jint baudrate)
{
    switch(baudrate) {
        case 0: return B0;
        case 50: return B50;
        case 75: return B75;
        case 110: return B110;
        case 134: return B134;
        case 150: return B150;
        case 200: return B200;
        case 300: return B300;
        case 600: return B600;
        case 1200: return B1200;
        case 1800: return B1800;
        case 2400: return B2400;
        case 4800: return B4800;
        case 9600: return B9600;
        case 19200: return B19200;
        case 38400: return B38400;
        case 57600: return B57600;
        case 115200: return B115200;
        case 230400: return B230400;
        case 460800: return B460800;
        case 500000: return B500000;
        case 576000: return B576000;
        case 921600: return B921600;
        case 1000000: return B1000000;
        case 1152000: return B1152000;
        case 1500000: return B1500000;
        case 2000000: return B2000000;
        case 2500000: return B2500000;
        case 3000000: return B3000000;
        case 3500000: return B3500000;
        case 4000000: return B4000000;
        default: return 0xFFFFFFFF;
    }
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_micronet_dsc_resetrb_modemupdater_Port_open(JNIEnv *env, jobject thiz, jstring path, jint baudrate) {
    //const char *path = env->GetStringUTFChars(path_, 0);

    int fd;
    speed_t speed;
    jobject mFileDescriptor;

    /* Check arguments */

    speed = getBaudrate(baudrate);
    if (speed == 0xFFFFFFFF) {
        /* TODO: throw an exception */
        LOGE("%s", "Invalid baudrate");
        return NULL;
    }


    /* Opening device */

    jboolean iscopy;
    const char *path_utf = env->GetStringUTFChars(path, &iscopy);
    LOGI("Opening serial port %s", path_utf);
    /* fd = open(path_utf, O_RDWR | O_DIRECT | O_SYNC); */
    if( (fd = open(path_utf, O_RDWR | O_NOCTTY | O_NONBLOCK)) < 0 )
    {
        //LOGE("Cannot open port: %s", strerror(errno));
        env->ReleaseStringUTFChars(path, path_utf);
        return NULL;
    }
    LOGI("open() fd = %d", fd);


    /* Configure device */
    struct termios cfg;
    LOGI("Configuring serial port");
    if (tcgetattr(fd, &cfg))
    {
        LOGE("%s", "tcgetattr() failed");
        close(fd);
        /* TODO: throw an exception */
        return NULL;
    }

    cfmakeraw(&cfg);
    cfsetispeed(&cfg, speed);
    cfsetospeed(&cfg, speed);
    // It was important to not include flags that added other bytes to be sent. Example: newlines after each send.
    cfg.c_cflag =  (B9600 | CLOCAL | CRTSCTS | CREAD | CS8 | HUPCL);
    cfg.c_iflag = (ICRNL | IXON);
    cfg.c_oflag = (ONLCR | NL0 | CR0 | TAB0 | BS0 | VT0 | FF0);
    cfg.c_lflag = 0;         /*disable ECHO, ICANON, etc...*/ /*
    cfg.c_cc[VTIME] = 10;   *//* unit: 1/10 second. *//*
    cfg.c_cc[VMIN] = 1;     *//* minimal characters for reading */

    if (tcsetattr(fd, TCSANOW, &cfg))
    {
        LOGE("%s", "tcsetattr() failed");
        close(fd);
        /* TODO: throw an exception */
        return NULL;
    }

    tcflush(fd, TCIOFLUSH);

    /* Create a corresponding file descriptor */
    jclass cFileDescriptor = env->FindClass("java/io/FileDescriptor");
    jmethodID iFileDescriptor = env->GetMethodID(cFileDescriptor, "<init>", "()V");
    jfieldID descriptorID = env->GetFieldID(cFileDescriptor, "descriptor", "I");
    mFileDescriptor = env->NewObject(cFileDescriptor, iFileDescriptor);
    env->SetIntField(mFileDescriptor, descriptorID, (jint)fd);


    return mFileDescriptor;
}

extern "C"
JNIEXPORT void JNICALL Java_com_micronet_dsc_resetrb_modemupdater_Port_close
        (JNIEnv *env, jobject thiz)
{
    jclass SerialPortClass = env->GetObjectClass(thiz);
    jclass FileDescriptorClass = env->FindClass("java/io/FileDescriptor");

    jfieldID mFdID = env->GetFieldID(SerialPortClass, "mFd", "Ljava/io/FileDescriptor;");
    jfieldID descriptorID = env->GetFieldID(FileDescriptorClass, "descriptor", "I");

    jobject mFd = env->GetObjectField(thiz, mFdID);
    jint descriptor = env->GetIntField(mFd, descriptorID);

    LOGI("close(fd = %d)", descriptor);
    close(descriptor);
}
