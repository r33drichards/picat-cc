--[[===========================================================================
  quarry_turtle.lua -- drive a CC:Tweaked mining turtle from a Picat plan.

  This is a REAL, runnable executor for the small quarry config in
  examples/quarry_small.pi (chunk 2x2, layers y=1 and y=0 -> a 2x2x2 pit).
  It asks the Picat engine for a safe, fuel-aware plan and then carries it
  out: mining 8 blocks, dumping to a chest when the inventory would overflow,
  and refuelling from a fuel chest when needed.

  -------------------------------------------------------------------------
  REQUIRED SETUP (place exactly like this, then run `quarry_turtle`):
  -------------------------------------------------------------------------
  Both example files must be on the turtle's filesystem:
      quarry_small.pi   (the planner program)
      quarry_turtle.lua (this script)
  See docs/acceptance.md for how to get them onto the turtle.

  The turtle needs:
    * a pickaxe equipped (turtle.dig must work),
    * some fuel already in it (the planner assumes fuel_cap = 200; a few
      coal/charcoal is plenty for the 2x2x2 case),
    * empty-ish inventory (it dumps when it reaches inv_cap = 4 blocks).

  Coordinate model (matches quarry_small.pi):
    * The model uses absolute coords {x, y, z}, x,z in 0..1, y descending.
    * surface_y = 1, bedrock_y = 0, so blocks are mined at y=1 then y=0.
    * The turtle and the chests sit on a PLATFORM one block above the pit,
      at model y = 2 (surface_y + 1).
    * start_pos     = {0, 2, 0}   (where the turtle begins, ON the platform)
    * storage_chest = {0, 2, 0}   (chest the turtle dumps mined blocks into)
    * fuel_chest    = {1, 2, 0}   (chest the turtle pulls fuel from)

  PHYSICAL LAYOUT -- look down at the platform (y = 2), x increases east,
  z increases south, and the turtle starts FACING SOUTH (+z):

        x=0        x=1
       +---------+---------+
  z=0  | STORAGE |  FUEL   |   <- two chests on the platform
       | (turtle |  CHEST  |
       | starts  |         |
       |  here)  |         |
       +---------+---------+
       (no z=1 row of chests; the pit opens below this platform)

  Place the STORAGE chest at the start_pos block, the turtle ON TOP of /
  beside it... -- concretely: stand the turtle on the platform block {0,2,0}
  facing +z (south). Put the STORAGE chest in that same column just so the
  turtle can drop into it, and the FUEL chest one block east ({1,2,0}).

  Because start_pos == storage_chest in this config, "go home to dump" and
  "return to start" are the same spot; the turtle drops mined blocks DOWN
  into the storage chest directly below the platform block it started on.

  SIMPLIFYING ASSUMPTIONS (honest notes -- fine for the 2x2x2 demo):
    * The 2x2 column of air ABOVE the pit (the platform layer, y=2) is clear
      so the turtle can traverse it freely. The turtle digs anything in its
      way regardless, so stray blocks on the platform are tolerated.
    * The storage chest sits directly UNDER the start/platform block, and the
      fuel chest directly under the {1,2,0} platform block, so dump/refuel
      use digDown-free drop/suck DOWNWARD. If you instead place the chests on
      the platform level beside the turtle, change DUMP/REFUEL to drop()/suck()
      forward -- see the dump()/refuel() functions, they are clearly marked.
    * Heading is tracked in software; the turtle MUST start facing +z (south).
  ===========================================================================]]

-- ---------------------------------------------------------------------------
-- config mirrored from quarry_small.pi (kept in sync by hand; the plan from
-- Picat is authoritative for WHAT to do, these are only for navigation math)
-- ---------------------------------------------------------------------------
local PLAN_FILE   = "quarry_small.pi"
local PLATFORM_Y  = 2          -- surface_y + 1; turtle/chest level
local START       = { x = 0, y = PLATFORM_Y, z = 0 }
local STORAGE     = { x = 0, y = PLATFORM_Y, z = 0 }
local FUEL_CHEST  = { x = 1, y = PLATFORM_Y, z = 0 }

-- ---------------------------------------------------------------------------
-- turtle state: position in MODEL coords + heading.
-- heading is a unit vector on the x/z plane. The turtle starts facing +z.
-- ---------------------------------------------------------------------------
local pos = { x = START.x, y = START.y, z = START.z }
local heading = { dx = 0, dz = 1 }   -- +z (south)

local function fail(msg)
  print("quarry: " .. msg)
  error(msg, 0)
end

-- turn helpers -------------------------------------------------------------
-- Rotating right: (dx,dz) -> (dz,-dx). Rotating left: (dx,dz) -> (-dz,dx).
local function turnRight()
  turtle.turnRight()
  heading = { dx = heading.dz, dz = -heading.dx }
end

local function turnLeft()
  turtle.turnLeft()
  heading = { dx = -heading.dz, dz = heading.dx }
end

-- Rotate the turtle until it faces (dx,dz). At most two turns.
local function face(dx, dz)
  while not (heading.dx == dx and heading.dz == dz) do
    -- If a single right turn gets us there, do it; otherwise turn left.
    if heading.dz == dx and -heading.dx == dz then
      turnRight()
    else
      turnLeft()
    end
  end
end

-- movement -----------------------------------------------------------------
-- Move forward one block, digging (and clearing gravel/sand falls) if blocked.
local function forward()
  local tries = 0
  while not turtle.forward() do
    turtle.dig()              -- clear the block ahead
    tries = tries + 1
    if not turtle.forward() then
      sleep(0.4)              -- let sand/gravel settle, then retry
    end
    if tries > 16 then fail("stuck moving forward at " .. pos.x .. "," .. pos.z) end
  end
  pos.x = pos.x + heading.dx
  pos.z = pos.z + heading.dz
end

local function down()
  local tries = 0
  while not turtle.down() do
    turtle.digDown()
    tries = tries + 1
    if tries > 16 then fail("stuck moving down at y=" .. pos.y) end
  end
  pos.y = pos.y - 1           -- model y DEScends as we go down
end

local function up()
  local tries = 0
  while not turtle.up() do
    turtle.digUp()
    tries = tries + 1
    if tries > 16 then fail("stuck moving up at y=" .. pos.y) end
  end
  pos.y = pos.y + 1
end

-- Navigate to a model coordinate, axis by axis. Horizontal moves happen
-- first (digging through anything), then vertical. Digging on the way means
-- the turtle mines whatever block sits at the target -- exactly the semantics
-- the planner intends for $mine(Tgt).
local function goTo(tx, ty, tz)
  -- x axis
  while pos.x < tx do face(1, 0);  forward() end
  while pos.x > tx do face(-1, 0); forward() end
  -- z axis
  while pos.z < tz do face(0, 1);  forward() end
  while pos.z > tz do face(0, -1); forward() end
  -- y axis
  while pos.y > ty do down() end   -- target deeper than us -> go down
  while pos.y < ty do up()   end   -- target above us       -> go up
end

-- ---------------------------------------------------------------------------
-- action handlers
-- ---------------------------------------------------------------------------

-- mine(Tgt): the planner mines the block AT Tgt. We navigate the turtle to
-- occupy that coordinate; goTo digs through it on the way, which clears it.
local function doMine(tx, ty, tz)
  print(string.format("  mine {%d,%d,%d}", tx, ty, tz))
  goTo(tx, ty, tz)
end

-- dump: return to the storage chest column and drop the inventory DOWN into
-- the chest sitting directly below the platform start block.
-- (If your chest is instead on the platform level beside the turtle, replace
--  the dropDown() loop with: face toward the chest, then turtle.drop().)
local function doDump()
  print("  dump -> storage chest")
  goTo(STORAGE.x, STORAGE.y, STORAGE.z)     -- back up to the platform block
  for slot = 1, 16 do
    turtle.select(slot)
    turtle.dropDown()                       -- into chest below the platform
  end
  turtle.select(1)
end

-- refuel: go to the fuel chest column, pull fuel DOWN out of it, and refuel.
-- (Same note as dump: if the fuel chest is beside the turtle on the platform,
--  use suck()/drop-forward instead of suckDown().)
local function doRefuel()
  print("  refuel <- fuel chest")
  goTo(FUEL_CHEST.x, FUEL_CHEST.y, FUEL_CHEST.z)
  turtle.select(1)
  turtle.suckDown()                         -- grab fuel from the chest below
  turtle.refuel()                           -- consume whatever fuel we hold
end

-- ---------------------------------------------------------------------------
-- plan element dispatch.
-- Marshalling (per the mod's contract):
--   "dump"/"refuel"     arrive as plain Lua strings (Picat atoms)
--   $mine(Tgt)          arrives as { f = "mine", args = { {x,y,z} } }
-- ---------------------------------------------------------------------------
local function runAction(a)
  if type(a) == "string" then
    if a == "dump" then
      doDump()
    elseif a == "refuel" then
      doRefuel()
    else
      fail("unknown atom action: " .. a)
    end
  elseif type(a) == "table" and a.f == "mine" then
    local tgt = a.args and a.args[1]
    if type(tgt) ~= "table" or #tgt < 3 then
      fail("mine action missing {x,y,z} target")
    end
    doMine(tgt[1], tgt[2], tgt[3])
  else
    fail("unrecognised plan element: " .. textutils.serialize(a))
  end
end

-- ---------------------------------------------------------------------------
-- main
-- ---------------------------------------------------------------------------
local function main()
  if type(picat) ~= "table" or type(picat.query) ~= "function" then
    fail("picat global not available -- is the picat-cc mod installed?")
  end
  if not turtle then
    fail("this script must run on a TURTLE (no turtle API found)")
  end

  -- read the planner program off the turtle's filesystem
  local fh = fs.open(PLAN_FILE, "r")
  if not fh then fail("cannot open " .. PLAN_FILE .. " on this turtle") end
  local prog = fh.readAll()
  fh.close()

  print("quarry: asking Picat for a safe plan...")
  local ok, sols = picat.query(prog, "do_plan(Plan, Cost)",
                               { "Plan", "Cost" }, { timeout = 120 })
  if not ok then
    fail("planner failed: " .. tostring(sols))
  end
  if type(sols) ~= "table" or not sols[1] or type(sols[1].Plan) ~= "table" then
    fail("planner returned no usable plan")
  end

  local plan = sols[1].Plan
  local cost = sols[1].Cost
  print(string.format("quarry: got plan with %d actions (travel cost %s)",
                      #plan, tostring(cost)))

  for i = 1, #plan do
    runAction(plan[i])
  end

  -- tidy up: come back to the start block on the platform
  goTo(START.x, START.y, START.z)
  face(0, 1)
  print("quarry: done. 2x2x2 pit mined.")
end

main()
