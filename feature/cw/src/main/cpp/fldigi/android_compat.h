// ----------------------------------------------------------------------------
// android_compat.h  --  fldigi desktop compatibility stubs for Android
// Replaces global variables that fldigi defines in fl_digi.h, configuration.h, etc.
// ----------------------------------------------------------------------------
#ifndef ANDROID_COMPAT_H
#define ANDROID_COMPAT_H

#include <string>

// Waterfall stub
struct waterfall_stub {
    double Carrier() { return 700.0; }
    bool Reverse() { return false; }
    bool USB() { return true; }
    void Bandwidth(int) {}
};
extern waterfall_stub* wf;

// progdefaults - fldigi global configuration
struct AndroidProgDefaults {
    int CWspeed = 18;
    double CWbandwidth = 200.0;
    double CWfarnsworth = 18;
    double CWupper = 0.8;
    double CWlower = 0.2;
    bool CWmfilt = true;
    bool CWtrack = true;
    int CWsweetspot = 700;
    int CWnoise = '*';
    int CWrisetime = 4;
    int QSKshape = 0;
    int CWdash2dot = 3;
    int CW_cal_speed = 18;
    bool CWusefarnsworth = false;
    bool use_KNWDkeying = false;
    bool use_ELCTkeying = false;
    bool use_ICOMkeying = false;
    bool use_YAESUkeying = false;
    int CATkeying_compensation = 0;
    bool StartAtSweetSpot = false;
    bool CW_use_paren = false;
    std::string CW_prosigns;
    bool pretone = false;
    bool use_nanoIO = false;
    bool rx_lowercase = false;
    bool CWuseSOMdecoding = true;
    int CW_bandwidth = 200;
    int CW_upper = 80;
    int CW_lower = 20;

    // Additional fields referenced by cw.cxx
    int CWrange = 10;
    int CWlowerlimit = 5;
    int CWupperlimit = 60;
    int CWpre = 0;
    int CWpost = 0;
    int CWkeycomp = 0;
    int cwrx_attack = 0;
    int cwrx_decay = 0;
    int defCWspeed = 18;
    int QSK = 0;
    int QSKamp = 0;
    int QSKfrequency = 0;
    int QSKrisetime = 0;
    bool CW_KEYLINE = false;
    bool CW_KEYLINE_on_cat_port = false;
    bool CW_KEYLINE_on_ptt_port = false;
    bool PTT_KEYLINE = false;
    bool use_FLRIGkeying = false;
    int BaudRate = 0;
    std::string CW_KEYLINE_serial_port_name;
};
extern AndroidProgDefaults progdefaults;

// NanoIO globals
extern bool use_nanoIO;
void set_nanoWPM(int wpm);

// UI/status stubs (fldigi desktop helpers, no-op on Android)
void put_cwRcvWPM(int);
void put_MODEstatus(const char*, ...);
void set_scope_xaxis_1(double);
void set_scope_xaxis(double);
template<typename T, int N, int M>
void set_scope(mbuffer<T,N,M>&, int, bool) {}
void set_nanoCW();
void update_Status();

// Fldigi math helpers
#define TWOPI (2.0 * M_PI)
inline double decayavg(double average, double input, int weight) {
    if (weight <= 1) return input;
    return ((input - average) / (double)weight) + average;
}

// progStatus
struct AndroidProgStatus {
    int carrier = 0;
    bool WK_online = false;
    bool sqlonoff = false;
    double sldrSquelchValue = 0.0;
    bool show_channels = false;
};
extern AndroidProgStatus progStatus;

// Misc helpers that fldigi provides via misc.h / status.h / etc.
void set_scope_mode(int);
void put_rx_char(int c);
void put_echo_char(int c);

// Time helpers
double zmsec();
void MilliSleep(int);

#endif