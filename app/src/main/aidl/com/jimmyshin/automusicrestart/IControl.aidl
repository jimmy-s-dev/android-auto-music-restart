package com.jimmyshin.automusicrestart;
interface IControl {
    String inspect() = 0;
    String stopTarget() = 1;
    String preparePlayback() = 2;
    String inspectHistory() = 3;
    void destroy() = 16777114;
}
