/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package com.gemstone.gemfire.cache.query;


/**
 * Extension of CqListener. Adds two new methods to CqListener, one that
 *  is called when the cq is connected and one that is called when
 *  the cq is disconnected
 * 
 *
 * @author jhuynh 
 * @since 7.0
 */

public interface CqStatusListener extends CqListener {

  /**
   * Called when the cq loses connection with all servers
   */  
  public void onCqDisconnected();
 
  /**
   * Called when the cq establishes a connection with a server
   */
  public void onCqConnected();
}
