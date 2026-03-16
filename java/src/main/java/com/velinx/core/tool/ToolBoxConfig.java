package com.velinx.core.tool;

import java.util.List;

public interface ToolBoxConfig {
    void init();
    public void setEnableRead(boolean v);
    public void setEnableWrite(boolean v) ;
    public void setEnableEdit(boolean v);
    public void setEnableTerm(boolean v);
    public void setEnableGlob(boolean v);
    public void setEnableGrep(boolean v) ;
    public void setEnableWeather(boolean v);
    List<Object> getTools();

}
