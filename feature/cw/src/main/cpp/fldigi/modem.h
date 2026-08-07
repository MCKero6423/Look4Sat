// ----------------------------------------------------------------------------
// modem.h  --  minimal modem base class for fldigi CW decoder (Android)
// Extracted from fldigi/src/include/modem.h (GPL v3).
// ----------------------------------------------------------------------------
#ifndef _MODEM_H
#define _MODEM_H

#include <string>
#include <cmath>
#include "morse.h"
#include "filters.h"

#define OUTBUFSIZE 65536

enum trx_mode {
    MODE_CW = 0
};

struct mode_info_t {
    const char *sname;
    unsigned int iface_io;
};

namespace Digiscope {
    enum scope_mode { SCOPE, SCOPE2, PHASE, PHASE2, WATERFALL, NONE };
}

class modem {
public:
    static double frequency;
    static double tx_frequency;
    static bool freqlock;
    static unsigned long tx_sample_count;
    static unsigned int tx_sample_rate;
    static bool XMLRPC_CPS_TEST;

protected:
    cMorse *morse;
    trx_mode mode;
    bool stopflag;
    int fragmentsize;
    int samplerate;
    bool reverse;
    int sigsearch;
    bool sig_start;
    bool sig_stop;
    double bandwidth;
    double freqerr;
    double rx_corr;
    double tx_corr;
    double PTTphaseacc;
    double PTTchannel[OUTBUFSIZE];
    bool cwTrack;
    bool cwLock;
    double cwRcvWPM;
    double cwXmtWPM;
    double squelch;
    double metric;
    double syncpos;
    int backspaces;
    unsigned char *txstr;
    unsigned char *txptr;
    double outbuf[OUTBUFSIZE];
    bool historyON;
    Digiscope::scope_mode scopemode;
    int scptr;
    double s2n_ncount, s2n_sum, s2n_sum2, s2n_metric;
    bool s2n_valid;
    unsigned cap;
    std::string audio_filename;
    bool play_audio;
    bool CW_EOT;

public:
    modem();
    virtual ~modem() { delete morse; }
    virtual void init();
    virtual void tx_init() = 0;
    virtual void rx_init() = 0;
    virtual void restart() = 0;
    virtual void rx_flush() {}
    virtual int tx_process();
    virtual int rx_process(const double *, int len) = 0;
    virtual void Audio_filename(std::string nm) { audio_filename = nm; play_audio = true; }
    virtual void shutdown() {}
    virtual void set1(int, int) {}
    virtual void set2(int, int) {}
    virtual void makeTxViewer(int W, int H) {}
    virtual void searchDown() {}
    virtual void searchUp() {}
    void HistoryON(bool val) { historyON = val; }
    bool HistoryON() const { return historyON; }
    trx_mode get_mode() const { return mode; }
    const char *get_mode_name() const;
    unsigned int iface_io() const;
    virtual void set_freq(double);
    int get_freq() const { return (int)(frequency + 0.5); }
    void init_freqlock();
    void set_freqlock(bool);
    void set_sigsearch(int n) { sigsearch = n; freqerr = 0.0; }
    bool freqlocked() const { return freqlock; }
    double get_txfreq() const;
    double get_txfreq_woffset() const;
    void set_metric(double);
    void display_metric(double);
    double get_metric() const { return metric; }
    void set_reverse(bool on);
    bool get_reverse() const { return reverse; }
    double get_bandwidth() const { return bandwidth; }
    void set_bandwidth(double);
    int get_samplerate() const { return samplerate; }
    void set_samplerate(int);
    void init_queues();
    void ModulateXmtr(double *, int);
    void ModulateStereo(double *, double *, int, bool sample_flag = true);
    void ModulateVideo(double *, int);
    void ModulateVideoStereo(double *, double *, int, bool sample_flag = true);
    void videoText();
    void pretone();
    virtual void send_color_image(std::string) {}
    virtual void send_Grey_image(std::string) {}
    virtual void ifkp_send_image(std::string s = "", bool grey = false) {}
    virtual void ifkp_send_avatar() {}
    virtual void m_ifkp_send_avatar() {}
    virtual void thor_send_image(std::string s = "", bool grey = false) {}
    virtual void thor_send_avatar() {}
    virtual void m_thor_send_avatar() {}
    void set_stopflag(bool b) { stopflag = b; }
    bool get_stopflag() const { return stopflag; }
    unsigned get_cap() const { return cap; }
    enum { CAP_AFC = 1 << 0, CAP_AFC_SR = 1 << 1, CAP_REV = 1 << 2,
           CAP_IMG = 1 << 3, CAP_BW = 1 << 4, CAP_RX = 1 << 5,
           CAP_TX = 1 << 6 };

    bool get_cwTrack();
    void set_cwTrack(bool);
    bool get_cwLock();
    void set_cwLock(bool);
    double get_cwXmtWPM();
    void set_cwXmtWPM(double);
    double get_cwRcvWPM();
    virtual void CW_KEYLINE(bool) {}
    virtual void incWPM() {}
    virtual void decWPM() {}
    virtual void toggleWPM() {}
    virtual void sync_parameters() {}
    virtual void reset_rx_filter() {}
    virtual void update_Status() {}
    virtual void refresh_scope() {}
    virtual void clear_viewer() {}
    virtual void clear_ch(int n) {}
    virtual int viewer_get_freq(int n) { return 0; }
    double calWPM() { return 20; }
    void calWPM(double) {}
    void resetFSK() {}
    void s2nreport() {}
    void set_scope_mode(Digiscope::scope_mode sm) { scopemode = sm; }
};

#endif