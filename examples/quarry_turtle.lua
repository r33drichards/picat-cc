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
      The 2x2x2 PIT therefore occupies x in {0,1}, z in {0,1}, y in {1,0}.
    * The turtle and the chests sit on a PLATFORM one block above the pit,
      at model y = 2 (surface_y + 1).
    * start_pos = {0, 2, 0}  -- where the turtle begins, ON the platform,
      directly above the {0,*,0} pit column.

  IMPORTANT -- the chests must NOT sit in the pit's x/z footprint. The planner
  mines every {x in 0..1, z in 0..1} column down to y=0, so any chest at those
  x/z (at any height in/under the pit) would be mined or dropped into the open
  hole. We therefore place the chests on the platform level (y=2) just NORTH of
  the pit, at z=-1, OUTSIDE the footprint, and the turtle drops/sucks FORWARD
  (not downward) into them. This differs from the storage_chest/fuel_chest
  COORDS in the .pi: those coords only feed the planner's fuel/distance math
  (Manhattan cost), they do not have to be the turtle's real drop spot. The
  physical chests below are what matter for the demo to actually work.

  PHYSICAL LAYOUT -- look down at the platform (y = 2), x increases east,
  z increases south, and the turtle starts FACING SOUTH (+z):

           x=0        x=1
         +---------+---------+
  z=-1   | STORAGE |  FUEL   |   <- two chests, NORTH of the pit (outside it)
         | CHEST   |  CHEST  |
         +---------+---------+
  z=0    | turtle  |         |   <- platform over the pit; turtle starts here
         | start   |         |      facing SOUTH (+z), back to the chests
         +---------+---------+
  z=1    |         |         |   <- rest of the platform over the pit
         +---------+---------+
         (the 2x2x2 pit is the x in {0,1}, z in {0,1} columns, BELOW y=2)

  Concretely:
    * Stand the turtle on platform block {0,2,0} FACING SOUTH (+z).
    * Place a STORAGE chest at {0,2,-1} (one block NORTH of the start).
    * Place a FUEL chest at {1,2,-1} (one block NORTH of {1,2,0}).
    * To dump: turtle goes to {0,2,0}, faces NORTH (-z), turtle.drop().
    * To refuel: turtle goes to {1,2,0}, faces NORTH (-z), turtle.suck()+refuel.
    The drop/suck cells {0,2,0} and {1,2,0} are at y=2 (platform) and the chest
    cells are at z=-1, so none of them ever coincides with a mined block.

  SIMPLIFYING ASSUMPTIONS (honest notes -- fine for the 2x2x2 demo):
    * The platform layer (y=2) over the 2x2 area is clear air so the turtle can
      traverse it. The turtle digs anything in its way regardless, so a stray
      block on the platform is tolerated -- but do NOT put the chests anywhere
      in the x in {0,1}, z in {0,1} columns or they WILL be mined.
    * Chests are placed exactly at {0,2,-1} (storage) and {1,2,-1} (fuel) so the
      FORWARD drop/suck in doDump/doRefuel lands in them.
    * Heading is tracked in software; the turtle MUST start facing +z (south).
  ===========================================================================]]

-- ---------------------------------------------------------------------------
-- config mirrored from quarry_small.pi (kept in sync by hand; the plan from
-- Picat is authoritative for WHAT to do, these are only for navigation math)
-- ---------------------------------------------------------------------------
local PLAN_FILE   = "quarry_small.pi"
local PLATFORM_Y  = 2          -- surface_y + 1; turtle/chest level
local START       = { x = 0, y = PLATFORM_Y, z = 0 }

-- Where the turtle STANDS to use each chest (platform cells, outside the pit).
-- The actual chests sit one block NORTH (z-1) of these; the turtle faces
-- NORTH (-z) and drops/sucks FORWARD. These cells are at y=2 and z>=0, so they
-- never coincide with a mined block (mined blocks are at y in {1,0}).
local STORAGE_AT  = { x = 0, y = PLATFORM_Y, z = 0 }   -- chest physically at {0,2,-1}
local FUEL_AT     = { x = 1, y = PLATFORM_Y, z = 0 }   -- chest physically at {1,2,-1}
local CHEST_DX, CHEST_DZ = 0, -1                        -- face NORTH to reach a chest

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
  -- Exactly ONE position-advancing move happens: we retry the SAME forward()
  -- after clearing the block ahead. We never call forward() a second time per
  -- iteration, so pos advances once and only once when it finally succeeds.
  while not turtle.forward() do
    turtle.dig()              -- clear the block ahead
    sleep(0.4)                -- let sand/gravel settle before retrying
    tries = tries + 1
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
  -- %g, not %d: coords arrive as Lua numbers (doubles); %d errors in Lua 5.3+.
  print(string.format("  mine {%g,%g,%g}", tx, ty, tz))
  goTo(tx, ty, tz)
end

-- Empty every slot FORWARD into whatever block the turtle is facing.
local function dropAllForward()
  for slot = 1, 16 do
    turtle.select(slot)
    turtle.drop()                           -- forward, into the chest ahead
  end
  turtle.select(1)
end

-- dump: stand on the platform cell in front of the storage chest, face it
-- (NORTH), and drop the whole inventory FORWARD into it. The chest sits at
-- z = STORAGE_AT.z - 1, OUTSIDE the mined footprint, so it is never disturbed.
local function doDump()
  print("  dump -> storage chest")
  goTo(STORAGE_AT.x, STORAGE_AT.y, STORAGE_AT.z)
  face(CHEST_DX, CHEST_DZ)                   -- face NORTH toward the chest
  dropAllForward()
end

-- refuel: stand in front of the fuel chest, face it, suck fuel FORWARD out of
-- it and burn it. Fuel chest sits at z = FUEL_AT.z - 1, outside the footprint.
local function doRefuel()
  print("  refuel <- fuel chest")
  goTo(FUEL_AT.x, FUEL_AT.y, FUEL_AT.z)
  face(CHEST_DX, CHEST_DZ)                   -- face NORTH toward the chest
  turtle.select(1)
  turtle.suck()                              -- grab fuel from the chest ahead
  turtle.refuel()                            -- consume whatever fuel we hold
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

  -- The planner's final/1 only requires Mined == total_blocks(), so an optimal
  -- plan ends right after the last $mine with up to inv_cap blocks still in the
  -- turtle and NO trailing dump. Empty the inventory here so the mined blocks
  -- actually end up in the storage chest (handled Lua-side; the .pi is unchanged).
  print("quarry: final dump of remaining inventory")
  doDump()

  -- tidy up: come back to the start block on the platform, facing south
  goTo(START.x, START.y, START.z)
  face(0, 1)
  print("quarry: done. 2x2x2 pit mined.")
end

main()
