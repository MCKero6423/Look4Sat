// ----------------------------------------------------------------------------
// view_cw.h  --  placeholder for fldigi CW waterfall view (Android)
// Replaces fldigi/src/include/view_cw.h (FLTK-based). Stub for compilation.
// ----------------------------------------------------------------------------
#ifndef _VIEW_CW_H
#define _VIEW_CW_H

#include <vector>

class view_cw {
public:
    view_cw() {}
    ~view_cw() {}

    void restart() {}

    void setFreq(double freq) {}
    void setSampleRate(int sr) {}

    // Data buffer for waterfall (populated by cw decoder, read by Android UI)
    std::vector<float> spectrum;
};

#endif