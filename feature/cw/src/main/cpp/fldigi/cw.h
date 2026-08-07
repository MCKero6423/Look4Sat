// ----------------------------------------------------------------------------
// cw.h  --  morse code modem (Android adaptation)
// Copyright (C) 2006-2009 Dave Freese, W1HKJ
// Adapted from fldigi/src/include/cw.h (GPL v3).
// ----------------------------------------------------------------------------
#ifndef _CW_H
#define _CW_H

#include <cstring>
#include <string>
#include <vector>

#include "modem.h"
#include "filters.h"
#include "fftfilt.h"
#include "mbuffer.h"
#include "view_cw.h"

#define CW_SAMPLERATE    8000
#define CWMaxSymLen      4096
#define MAX_MORSE_ELEMENTS 6
#define CW_SUCCESS       0
#define CW_ERROR         -1
#define ASC_NUL          '\0'
#define ASC_SPACE        ' '
#define KWPM             (12 * CW_SAMPLERATE / 10)
#define CWKNUM           ((KWPM) / 10)
#define TONE_SILENT      0
#define USECS_PER_SEC    1000000
#define INITIAL_SEND_SPEED      18
#define INITIAL_RECEIVE_SPEED   18
#define INITIAL_THRESHOLD       (((KWPM) / INITIAL_RECEIVE_SPEED) * 2)
#define INITIAL_NOISE_THRESHOLD (((KWPM) / CW_MAX_SPEED) / 2)
#define TRACKING_FILTER_SIZE 16
#define MAX_PIPE_SIZE (22 * CW_SAMPLERATE * 12 / 800)
#define CW_MAX_SPEED 100

enum CW_RX_STATE {
    RS_IDLE = 0,
    RS_IN_TONE,
    RS_AFTER_TONE
};

enum CW_EVENT {
    CW_RESET_EVENT,
    CW_KEYDOWN_EVENT,
    CW_KEYUP_EVENT,
    CW_QUERY_EVENT
};

class cw : public modem {
public:
#define CLRCOUNT 16
#define DEC_RATIO 16
#define WGT_SIZE 7

    struct SOM_TABLE {
        std::string rpr;
        float wgt[WGT_SIZE];
    };

protected:
    int symbollen;
    int fsymlen;
    double phaseacc;
    double FFTphase;
    double FFTvalue;
    unsigned int smpl_ctr;
    double agc_peak;
    bool use_matched_filter;
    double upper_threshold;
    double lower_threshold;
    fftfilt *cw_FFT_filter;
    Cmovavg *bitfilter;
    Cmovavg *trackingfilter;
    int bitfilterlen;
    CW_RX_STATE cw_receive_state;
    CW_RX_STATE old_cw_receive_state;
    CW_EVENT cw_event;
    double pipe[MAX_PIPE_SIZE + 1];
    double clearpipe[MAX_PIPE_SIZE + 1];
    mbuffer<double, MAX_PIPE_SIZE + 1, 4> scopedata;
    int pipeptr;
    int pipesize;
    bool scope_clear;

    // Config (from progdefaults, replaced for Android)
    int cw_speed;
    int cw_bandwidth;
    int cw_squelch;
    int cw_send_speed;
    int cw_receive_speed;
    bool usedefaultWPM;
    int cw_upper_limit;
    int cw_lower_limit;
    long int cw_noise_spike_threshold;
    int cw_in_sync;
    long int cw_send_dot_length;
    long int cw_send_dash_length;
    int lastsym;
    double risetime;
    int knum;
    int qnum;
    int QSKshape;
    double qskbuf[OUTBUFSIZE];
    double qskphase;
    bool firstelement;
    bool lastelement;
    double maxval;
    long int cw_receive_dot_length;
    long int cw_receive_dash_length;
    std::string rx_rep_buf;
    int cw_rr_current;
    unsigned int cw_rr_start_timestamp;
    unsigned int cw_rr_end_timestamp;
    long int two_dots;
    int in_replay;
    double dot_tracking;
    double dash_tracking;

    // Android additions
    std::string rx_text_buffer;  // decoded text for JNI retrieval

    inline double nco(double freq);
    inline double qsknco();
    void update_syncscope();
    void clear_syncscope();
    void update_Status();
    void sync_parameters();
    void reset_rx_filter();
    int handle_event(int cw_event, std::string &sc);
    inline int usec_diff(unsigned int earlier, unsigned int later);
    void send_symbol(int symbol, int len, int state);
    void send_ch(int c);
    bool tables_init();
    unsigned int tokenize_representation(char *representation);
    void update_tracking(int dot, int dash);

    static const SOM_TABLE som_table[];
    float cw_buffer[512];
    int cw_ptr;
    int clrcount;
    double lowerwpm;
    double upperwpm;
    int synchscope;
    double noise_floor;
    double sig_avg;
    double siglevel;
    bool use_paren;
    std::string prosigns;
    cmplx mixer(cmplx in);

    int nusymbollen;
    int nufsymlen;
    int kpre;
    int kpost;
    double wpm;
    double fwpm;
    double cal_wpm;
    void create_edges();
    void sync_transmit_parameters();
    void flush_audio();
    void send_CW(int);
    view_cw viewcw;

public:
    cw();
    ~cw();
    void init();
    void rx_init();
    void tx_init();
    void restart() {}

    int rx_process(const double *buf, int len);
    void rx_FFTprocess(const double *buf, int len);
    void rx_FIRprocess(const double *buf, int len);
    void decode_stream(double);

    int tx_process();
    void incWPM();
    void decWPM();
    void toggleWPM();
    double calWPM() { return cal_wpm; }
    void calWPM(double val) { cal_wpm = val; }

    int normalize(float *v, int n, int twodots);
    std::string find_winner(float *inbuf, int twodots);

    // Android: get and clear decoded text buffer
    std::string get_rx_text() {
        std::string result = rx_text_buffer;
        rx_text_buffer.clear();
        return result;
    }
};

#endif